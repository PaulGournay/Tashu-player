package com.tashu.player.playback.datasource

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.tashu.player.drive.api.DriveApi
import com.tashu.player.drive.auth.GoogleAuthManager

/**
 * DataSource ExoPlayer pour les URIs `gdrive://<fileId>`.
 *
 * - Réécrit l'URI vers `https://www.googleapis.com/drive/v3/files/<id>?alt=media`.
 * - Injecte `Authorization: Bearer <token>` (token obtenu/rafraîchi par [GoogleAuthManager]).
 * - Le seek ExoPlayer se traduit par `DataSpec.position/length` → header `Range: bytes=a-b`
 *   posé par le [HttpDataSource] sous-jacent (OkHttp) : streaming HTTP Range natif.
 * - Sur HTTP 401 (token expiré/révoqué entre deux pistes) : invalidation + nouvel essai unique.
 *
 * `open()` est appelé sur le thread de chargement d'ExoPlayer : les appels bloquants au
 * gestionnaire d'auth y sont autorisés (jamais sur le main thread).
 */
@UnstableApi
class GoogleDriveDataSource(
    private val upstream: HttpDataSource,
    private val auth: GoogleAuthManager,
) : DataSource {

    private var originalUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        originalUri = dataSpec.uri
        val fileId = fileIdOf(dataSpec.uri)
        var token = auth.accessTokenBlocking()
        return try {
            upstream.open(authorized(dataSpec, fileId, token))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 401) throw e
            upstream.close()
            auth.invalidateBlocking(token)
            token = auth.accessTokenBlocking()
            upstream.open(authorized(dataSpec, fileId, token))
        }
    }

    private fun authorized(spec: DataSpec, fileId: String, token: String): DataSpec =
        spec.buildUpon()
            .setUri(Uri.parse(DriveApi.mediaUrl(fileId)))
            .setHttpRequestHeaders(spec.httpRequestHeaders + ("Authorization" to "Bearer $token"))
            .build()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = originalUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        originalUri = null
        upstream.close()
    }

    class Factory(
        private val httpFactory: HttpDataSource.Factory,
        private val auth: GoogleAuthManager,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = GoogleDriveDataSource(httpFactory.createDataSource(), auth)
    }

    companion object {
        const val SCHEME = "gdrive"
        fun uriFor(fileId: String): Uri = Uri.parse("$SCHEME://$fileId")
        fun fileIdOf(uri: Uri): String {
            require(uri.scheme == SCHEME) { "URI non Drive : $uri" }
            return requireNotNull(uri.host ?: uri.lastPathSegment) { "fileId manquant : $uri" }
        }
    }
}
