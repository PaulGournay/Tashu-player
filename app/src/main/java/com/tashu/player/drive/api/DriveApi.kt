package com.tashu.player.drive.api

import com.tashu.player.drive.auth.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val size: Long? = null,
    val md5Checksum: String? = null,
    val modifiedTime: String = "",
)

@Serializable
data class DriveParents(val parents: List<String> = emptyList())

@Serializable
private data class FileList<T>(val files: List<T> = emptyList(), val nextPageToken: String? = null)

class DriveHttpException(val code: Int, msg: String) : IOException("Drive HTTP $code: $msg")

/**
 * Client minimal Google Drive REST API v3 (scope drive.readonly).
 * Chaque requête porte `Authorization: Bearer <token>` ; en cas de 401 le token est
 * invalidé puis la requête rejouée une fois.
 */
@Singleton
class DriveApi @Inject constructor(
    private val http: OkHttpClient,
    private val auth: GoogleAuthManager,
    private val json: Json,
) {
    /** Liste paginée des enfants directs d'un dossier. `folderId = "root"` = Mon Drive. */
    suspend fun listChildren(folderId: String, foldersOnly: Boolean = false): List<DriveFile> {
        val q = buildString {
            append("'").append(folderId.replace("'", "\\'")).append("' in parents and trashed = false")
            if (foldersOnly) append(" and mimeType = '$FOLDER_MIME'")
        }
        return listQuery(q)
    }

    /** Dossiers partagés avec moi (racine virtuelle du sélecteur). */
    suspend fun sharedFolders(): List<DriveFile> =
        listQuery("sharedWithMe = true and mimeType = '$FOLDER_MIME' and trashed = false")

    /** Dossiers parents de chaque fichier audio visible (détection des dossiers de musique). */
    suspend fun audioParents(): List<DriveParents> =
        listQuery("mimeType contains 'audio/' and trashed = false", DriveParents.serializer(), "nextPageToken, files(parents)", orderBy = null)

    suspend fun allFolders(): List<DriveFile> =
        listQuery("mimeType = '$FOLDER_MIME' and trashed = false", DriveFile.serializer(), "nextPageToken, files(id,name,mimeType,parents)", orderBy = null)

    suspend fun file(id: String): DriveFile = withContext(Dispatchers.IO) {
        val url = "$BASE/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        execute(Request.Builder().url(url).build()).use { json.decodeFromString(DriveFile.serializer(), it.body!!.string()) }
    }

    private suspend fun listQuery(q: String): List<DriveFile> =
        listQuery(q, DriveFile.serializer(), "nextPageToken, files($FILE_FIELDS)", "folder,name_natural")

    private suspend fun <T> listQuery(q: String, item: KSerializer<T>, fields: String, orderBy: String?): List<T> = withContext(Dispatchers.IO) {
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val url = "$BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", fields)
                .addQueryParameter("pageSize", "1000")
                .apply { orderBy?.let { addQueryParameter("orderBy", it) } }
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .addQueryParameter("supportsAllDrives", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = execute(Request.Builder().url(url).build()).use {
                json.decodeFromString(FileList.serializer(item), it.body!!.string())
            }
            out += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        out
    }

    /** HTTP Range Request sur le contenu (`alt=media`) : lit [start, endInclusive]. */
    suspend fun readRange(fileId: String, start: Long, endInclusive: Long): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(mediaUrl(fileId)).header("Range", "bytes=$start-$endInclusive").build()
        execute(req).use { resp ->
            val bytes = resp.body!!.bytes()
            // Un serveur qui ignore Range renverrait 200 + fichier complet : on tronque.
            if (resp.code == 200 && bytes.size.toLong() > endInclusive - start + 1) {
                bytes.copyOfRange(start.toInt(), (endInclusive + 1).toInt().coerceAtMost(bytes.size))
            } else bytes
        }
    }

    /** Téléchargement complet d'un petit fichier (pochette, .lrc). */
    suspend fun download(fileId: String, target: File) = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(mediaUrl(fileId)).build()).use { resp ->
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
    }

    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(mediaUrl(fileId)).build()).use { it.body!!.string() }
    }

    // ---------------- Écriture (scope drive.file : uniquement ce que l'app a créé) ----------------

    /** Dossier enfant portant exactement ce nom, ou null. `parentId = "root"` = Mon Drive. */
    suspend fun findFolder(name: String, parentId: String): DriveFile? =
        listQuery(
            "name = '${escapeQuery(name)}' and '$parentId' in parents " +
                "and mimeType = '$FOLDER_MIME' and trashed = false"
        ).firstOrNull()

    suspend fun createFolder(name: String, parentId: String): DriveFile = withContext(Dispatchers.IO) {
        val meta = """{"name":${JsonPrimitive(name)},"mimeType":"$FOLDER_MIME","parents":["$parentId"]}"""
        val url = "$BASE/files".toHttpUrl().newBuilder().addQueryParameter("fields", FILE_FIELDS).build()
        execute(Request.Builder().url(url).post(meta.toRequestBody(JSON)).build()).use {
            json.decodeFromString(DriveFile.serializer(), it.body!!.string())
        }
    }

    suspend fun findOrCreateFolder(name: String, parentId: String): DriveFile =
        findFolder(name, parentId) ?: createFolder(name, parentId)

    /** Noms des fichiers déjà présents dans un dossier (pour ne pas renvoyer un morceau déjà importé). */
    suspend fun childNames(folderId: String): Set<String> = listChildren(folderId).map { it.name }.toSet()

    /**
     * Envoi « resumable » : une requête de métadonnées (renvoie l'URL de session), puis le contenu.
     * Convient aux fichiers de plusieurs dizaines de Mo.
     */
    suspend fun uploadFile(file: File, parentId: String, mimeType: String): DriveFile = withContext(Dispatchers.IO) {
        val meta = """{"name":${JsonPrimitive(file.name)},"parents":["$parentId"]}"""
        val start = "$UPLOAD/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val session = execute(
            Request.Builder().url(start)
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(meta.toRequestBody(JSON))
                .build()
        ).use { it.header("Location") ?: throw IOException("Drive : URL d'envoi absente") }
        execute(Request.Builder().url(session).put(file.asRequestBody(mimeType.toMediaType())).build()).use {
            json.decodeFromString(DriveFile.serializer(), it.body!!.string())
        }
    }

    private suspend fun execute(base: Request): Response {
        var token = auth.accessToken()
        var resp = http.newCall(base.newBuilder().header("Authorization", "Bearer $token").build()).execute()
        if (resp.code == 401) {
            resp.close()
            auth.invalidate(token)
            token = auth.accessToken()
            resp = http.newCall(base.newBuilder().header("Authorization", "Bearer $token").build()).execute()
        }
        if (!resp.isSuccessful) {
            val msg = resp.body?.string().orEmpty().take(300)
            resp.close()
            throw DriveHttpException(resp.code, msg)
        }
        return resp
    }

    companion object {
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,name,mimeType,parents,size,md5Checksum,modifiedTime"
        /** Échappement des valeurs dans une requête `q` Drive : antislash puis apostrophe. */
        fun escapeQuery(v: String) = v.replace("\\", "\\\\").replace("'", "\\'")
        fun mediaUrl(fileId: String) = "$BASE/files/$fileId?alt=media&supportsAllDrives=true"
    }
}
