package com.tashu.player.drive.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.tashu.player.data.db.AlbumEntity
import com.tashu.player.data.db.ArtistEntity
import com.tashu.player.data.db.ComposerEntity
import com.tashu.player.data.db.FolderEntity
import com.tashu.player.data.db.GenreEntity
import com.tashu.player.data.db.MusicDatabase
import com.tashu.player.data.db.TrackEntity
import com.tashu.player.domain.model.AudioCodec
import com.tashu.player.drive.api.DriveApi
import com.tashu.player.drive.api.DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class ScanProgress(
    val running: Boolean = false,
    val foldersVisited: Int = 0,
    val filesFound: Int = 0,
    val filesIndexed: Int = 0,
    val filesSkipped: Int = 0,
    val errors: Int = 0,
    val current: String? = null,
)

/**
 * Indexeur Google Drive → Room.
 *
 * 1. Parcours récursif (BFS) des dossiers racine choisis par l'utilisateur.
 * 2. Scan **incrémental** : un fichier dont md5Checksum/modifiedTime n'a pas changé est ignoré.
 * 3. Extraction des métadonnées **sans télécharger le fichier** : [DriveRangeReader] alimente
 *    `MediaMetadataRetriever` (tags standards + durée/bitrate) et [TagParser] (ReplayGain,
 *    paroles, STREAMINFO FLAC) par HTTP Range Requests de 64 Kio.
 * 4. Pochettes : `cover|folder|front.(jpg|png)` du dossier en priorité, sinon image embarquée.
 * 5. Fichiers `.lrc` frères (même nom de base) → paroles synchronisées.
 * 6. Suppression des pistes disparues de Drive + nettoyage des albums/artistes orphelins.
 */
@Singleton
class DriveMusicScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val db: MusicDatabase,
) {
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress

    private val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
    private val trackDao = db.trackDao()
    private val catalog = db.catalogDao()

    suspend fun scan(rootIds: Set<String>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val scanStart = System.currentTimeMillis()
        _progress.value = ScanProgress(running = true)
        val known = if (force) emptyMap() else trackDao.fingerprints().associateBy { it.id }
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val parallelism = Semaphore(PARALLEL_FILES)

        try {
            val queue = ArrayDeque<Pair<DriveFile, Boolean>>() // (dossier, estRacine)
            rootIds.forEach { id -> queue += api.file(id) to true }
            val visited = HashSet<String>()

            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (folder, isRoot) = queue.removeFirst()
                if (!visited.add(folder.id)) {
                    // Racine choisie qui est aussi le sous-dossier d'une autre racine : on la rattache à son parent.
                    if (!isRoot) db.folderDao().attachToParent(folder.id, folder.parents.first())
                    continue
                }
                update { it.copy(foldersVisited = it.foldersVisited + 1, current = folder.name) }

                val children = api.listChildren(folder.id)
                val subFolders = children.filter { it.mimeType == DriveApi.FOLDER_MIME }
                val audio = children.filter { it.isAudio() }
                val lrcByBase = children.filter { it.name.endsWith(".lrc", true) }.associateBy { it.name.substringBeforeLast('.').lowercase() }
                val cover = children
                    .filter { it.name.lowercase().substringBeforeLast('.') in COVER_NAMES && it.mimeType.startsWith("image/") }
                    // cover.gif / cover.webp (souvent animées) passent devant cover.jpg.
                    .minByOrNull { if (it.name.substringAfterLast('.', "").lowercase() in ANIMATED_EXT) 0 else 1 }

                db.folderDao().upsert(listOf(FolderEntity(folder.id, folder.name, if (isRoot) null else folder.parents.firstOrNull(), isRoot, cover?.id, scanStart)))
                subFolders.forEach { queue += it to false }
                update { it.copy(filesFound = it.filesFound + audio.size) }

                val folderArtwork = cover?.let { downloadCover(it) }
                if (audio.isEmpty()) continue
                val albumId = db.withTransaction {
                    val id = catalog.albumIdForFolder(folder.id) ?: catalog.insertAlbum(AlbumEntity(folderId = folder.id, artworkPath = folderArtwork))
                    folderArtwork?.let { catalog.setAlbumArtwork(id, it) }
                    id
                }

                coroutineScope {
                    for (file in audio) {
                        seen += file.id
                        val prev = known[file.id]
                        if (prev != null && prev.modifiedTime == file.modifiedTime && prev.md5 == file.md5Checksum) {
                            update { it.copy(filesSkipped = it.filesSkipped + 1) }
                            continue
                        }
                        launch {
                            parallelism.withPermit {
                                runCatching {
                                    indexFile(file, folder.id, albumId, lrcByBase[file.name.substringBeforeLast('.').lowercase()], folderArtwork, scanStart)
                                }.onSuccess {
                                    update { it.copy(filesIndexed = it.filesIndexed + 1) }
                                }.onFailure { e ->
                                    Log.w(TAG, "Échec indexation ${file.name}", e)
                                    update { it.copy(errors = it.errors + 1) }
                                }
                            }
                        }
                    }
                }
            }

            // Fichiers supprimés ou déplacés hors des racines.
            val removed = known.keys - seen
            if (force) {
                val all = trackDao.fingerprints().map { it.id }
                trackDao.delete(all.filterNot { it in seen })
            } else if (removed.isNotEmpty()) {
                removed.chunked(500).forEach { trackDao.delete(it) }
            }
            db.folderDao().deleteStale(scanStart)
            catalog.pruneOrphans()
            trackDao.backfillArtwork()
        } finally {
            update { it.copy(running = false, current = null) }
        }
    }

    private suspend fun indexFile(file: DriveFile, folderId: String, albumId: Long, lrc: DriveFile?, folderArtwork: String?, now: Long) {
        val size = file.size ?: return
        val reader = DriveRangeReader(api, file.id, size)
        val extra = TagParser.parse(reader)

        val mmr = MediaMetadataRetriever()
        var embeddedArt: ByteArray? = null
        val meta = HashMap<Int, String?>()
        try {
            mmr.setDataSource(reader.asMediaDataSource())
            KEYS.forEach { meta[it] = mmr.extractMetadata(it) }
            if (folderArtwork == null) embeddedArt = mmr.embeddedPicture
        } catch (e: Exception) {
            // ByteBudgetExceeded ou format non reconnu : on garde ce que TagParser a trouvé.
            Log.d(TAG, "MMR partiel pour ${file.name}: ${e.message}")
        } finally {
            runCatching { mmr.release() }
        }

        fun m(key: Int) = meta[key]?.takeIf { it.isNotBlank() }
        val c = extra.comments
        val title = m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: c["TITLE"] ?: file.name.substringBeforeLast('.')
        val artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: c["ARTIST"] ?: UNKNOWN_ARTIST
        val albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: c["ALBUMARTIST"] ?: c["ALBUM ARTIST"] ?: artist
        val album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: c["ALBUM"] ?: UNKNOWN_ALBUM
        val genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: c["GENRE"]
        val composer = m(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: c["COMPOSER"]
        val year = (m(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: c["DATE"])?.take(4)?.toIntOrNull()
        val trackNo = (m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: c["TRACKNUMBER"])?.substringBefore('/')?.trim()?.toIntOrNull()
        val discNo = (m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER) ?: c["DISCNUMBER"])?.substringBefore('/')?.trim()?.toIntOrNull()
        val mime = m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: file.mimeType

        var codec = AudioCodec.fromName(file.name, file.mimeType)
        if (codec == AudioCodec.AAC && mime.contains("alac", true)) codec = AudioCodec.ALAC
        val sampleRate = extra.sampleRate ?: if (Build.VERSION.SDK_INT >= 31) m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
        val bitDepth = extra.bitDepth ?: if (Build.VERSION.SDK_INT >= 31) m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() else null
        val durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: extra.totalSamples?.let { s -> sampleRate?.let { s * 1000 / it } } ?: 0L
        val bitrate = m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000)
            ?: if (durationMs > 0) (size * 8 / durationMs).toInt() else null

        // Catalogue : artistes / genres / compositeurs (insert-or-get).
        db.withTransaction {
            val artistId = catalog.artistId(albumArtist) ?: catalog.insertArtist(ArtistEntity(name = albumArtist))
            val artPath = folderArtwork ?: embeddedArt?.let { saveArtwork(folderId, it) } ?: catalog.albumArtwork(albumId)
            artPath?.let { catalog.setAlbumArtworkIfMissing(albumId, it) }
            val genreId = genre?.let { catalog.genreId(it) ?: catalog.insertGenre(GenreEntity(name = it)) }
            val composerId = composer?.let { catalog.composerId(it) ?: catalog.insertComposer(ComposerEntity(name = it)) }
            val existing = trackDao.byId(file.id)

            trackDao.upsert(
                TrackEntity(
                    id = file.id, title = title, artist = artist, albumArtist = albumArtist, album = album,
                    albumId = albumId, artistId = artistId, genre = genre, genreId = genreId,
                    composer = composer, composerId = composerId, trackNumber = trackNo, discNumber = discNo,
                    year = year, durationMs = durationMs, mimeType = mime, codec = codec.name,
                    bitrateKbps = bitrate, sampleRateHz = sampleRate, bitDepth = bitDepth, channels = extra.channels,
                    sizeBytes = size, folderId = folderId, fileName = file.name, md5 = file.md5Checksum,
                    modifiedTime = file.modifiedTime,
                    rgTrackGain = extra.rgTrackGain, rgTrackPeak = extra.rgTrackPeak,
                    rgAlbumGain = extra.rgAlbumGain, rgAlbumPeak = extra.rgAlbumPeak,
                    embeddedLyrics = extra.lyrics, lrcFileId = lrc?.id, artworkPath = artPath,
                    // Conserve les données d'usage lors d'une ré-indexation.
                    rating = existing?.rating ?: 0, playCount = existing?.playCount ?: 0,
                    lastPlayedAt = existing?.lastPlayedAt, dateAdded = existing?.dateAdded ?: now,
                )
            )
        }
    }

    private suspend fun downloadCover(file: DriveFile): String? = runCatching {
        val target = File(artworkDir, "folder_${file.id}.${file.name.substringAfterLast('.', "jpg")}")
        if (!target.exists() || target.length() != file.size) api.download(file.id, target)
        target.absolutePath
    }.getOrNull()

    private fun saveArtwork(key: String, bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(artworkDir, "emb_$hash.jpg")
        if (!f.exists()) f.writeBytes(bytes)
        return f.absolutePath
    }

    private fun update(block: (ScanProgress) -> ScanProgress) {
        synchronized(_progress) { _progress.value = block(_progress.value) }
    }

    private fun DriveFile.isAudio(): Boolean =
        mimeType.startsWith("audio/") ||
            name.substringAfterLast('.', "").lowercase() in AudioCodec.SUPPORTED_EXTENSIONS

    companion object {
        private const val TAG = "DriveMusicScanner"
        private const val PARALLEL_FILES = 6
        const val UNKNOWN_ARTIST = "Artiste inconnu"
        const val UNKNOWN_ALBUM = "Album inconnu"
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart", "foldercover", "thumbnail")
        private val ANIMATED_EXT = setOf("gif", "webp", "png", "apng")
        private val KEYS = intArrayOf(
            MediaMetadataRetriever.METADATA_KEY_TITLE, MediaMetadataRetriever.METADATA_KEY_ARTIST,
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, MediaMetadataRetriever.METADATA_KEY_ALBUM,
            MediaMetadataRetriever.METADATA_KEY_GENRE, MediaMetadataRetriever.METADATA_KEY_COMPOSER,
            MediaMetadataRetriever.METADATA_KEY_YEAR, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,
            MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER, MediaMetadataRetriever.METADATA_KEY_DURATION,
            MediaMetadataRetriever.METADATA_KEY_BITRATE, MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
        ) + if (Build.VERSION.SDK_INT >= 31) intArrayOf(
            MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE,
        ) else intArrayOf()
    }
}
