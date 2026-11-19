package com.tashu.player.data.lyrics

import com.tashu.player.BuildConfig
import com.tashu.player.domain.lyrics.LyricsCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class LrcLibRecord(
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** Recherche de paroles sur LRCLIB (https://lrclib.net), base libre et gratuite, sans clé. */
@Singleton
class LrcLibClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** `GET /api/search` par champs (titre, artiste, album) ou par texte libre ([query]). */
    suspend fun search(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        query: String? = null,
    ): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val url = "$BASE/api/search".toHttpUrl().newBuilder().apply {
            if (!query.isNullOrBlank()) addQueryParameter("q", query.trim())
            else {
                title?.takeIf { it.isNotBlank() }?.let { addQueryParameter("track_name", it) }
                artist?.takeIf { it.isNotBlank() }?.let { addQueryParameter("artist_name", it) }
                album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) }
            }
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("LRCLIB HTTP ${resp.code}")
            json.decodeFromString(ListSerializer(LrcLibRecord.serializer()), resp.body!!.string()).map {
                LyricsCandidate(
                    title = it.trackName, artist = it.artistName, album = it.albumName,
                    durationMs = it.duration?.let { s -> (s * 1000).toLong() },
                    instrumental = it.instrumental, syncedLyrics = it.syncedLyrics, plainLyrics = it.plainLyrics,
                )
            }
        }
    }

    private companion object {
        const val BASE = "https://lrclib.net"
        val USER_AGENT = "Tashu-player/${BuildConfig.VERSION_NAME}"
    }
}
