package com.tashu.player.data.lyrics

import android.util.Log
import android.util.LruCache
import com.tashu.player.data.db.LyricsEntity
import com.tashu.player.data.db.MusicDatabase
import com.tashu.player.data.db.TrackEntity
import com.tashu.player.domain.lyrics.LrcParser
import com.tashu.player.domain.lyrics.LyricsCandidate
import com.tashu.player.domain.lyrics.LyricsMatcher
import com.tashu.player.domain.repository.LyricsOption
import com.tashu.player.domain.repository.LyricsRepository
import com.tashu.player.domain.repository.LyricsSource
import com.tashu.player.domain.repository.LyricsState
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.drive.api.DriveApi
import com.tashu.player.drive.scan.DriveMusicScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LyricsRepositoryImpl @Inject constructor(
    db: MusicDatabase,
    private val api: DriveApi,
    private val lrcLib: LrcLibClient,
    private val settings: SettingsRepository,
) : LyricsRepository {
    private val tracks = db.trackDao()
    private val dao = db.lyricsDao()
    private val driveLrc = LruCache<String, String>(64)
    /** Pistes sans résultat en ligne : on ne redemande pas avant 6 h (en mémoire, comme Namida). */
    private val notFoundAt = ConcurrentHashMap<String, Long>()

    override fun lyrics(trackId: String): Flow<LyricsState> =
        combine(dao.observe(trackId), settings.lyricsOnline) { pref, online -> (pref ?: LyricsEntity(trackId)) to online }
            .transformLatest { (pref, online) ->
                val track = tracks.byId(trackId)
                if (track == null) { emit(LyricsState.None()); return@transformLatest }
                val local = localLyrics(track, pref)
                when {
                    pref.ignored -> emit(LyricsState.None(ignored = true))
                    local != null -> emit(LyricsState.Found(local.second, local.first, pref.offsetMs))
                    pref.onlineText != null -> emit(LyricsState.Found(pref.onlineText, LyricsSource.ONLINE, pref.offsetMs))
                    pref.onlineInstrumental -> emit(LyricsState.Instrumental)
                    !online || recentlyNotFound(trackId) -> emit(LyricsState.None())
                    else -> {
                        emit(LyricsState.Loading)
                        val found = try {
                            fetchOnline(track)
                        } catch (e: IOException) {
                            emit(LyricsState.None(error = "Recherche en ligne impossible (${e.message})")); return@transformLatest
                        } catch (e: SerializationException) {
                            emit(LyricsState.None(error = "Réponse LRCLIB illisible")); return@transformLatest
                        }
                        if (found == null) {
                            notFoundAt[trackId] = System.currentTimeMillis()
                            emit(LyricsState.None())
                        } else {
                            // L'écriture relance ce flux, qui émet alors les paroles trouvées.
                            dao.upsert(pref.copy(onlineText = found.second, onlineInstrumental = found.first, onlineFetchedAt = System.currentTimeMillis()))
                        }
                    }
                }
            }

    /** Sources locales, par priorité : choix manuel, tag synchronisé, .lrc Drive, tag en texte simple. */
    private suspend fun localLyrics(track: TrackEntity, pref: LyricsEntity): Pair<LyricsSource, String>? {
        pref.manualText?.let { return LyricsSource.MANUAL to it }
        val embedded = track.embeddedLyrics?.takeIf { it.isNotBlank() }
        if (embedded != null && LrcParser.isSynced(embedded)) return LyricsSource.EMBEDDED to embedded
        track.lrcFileId?.let { id -> driveText(id)?.let { return LyricsSource.DRIVE_LRC to it } }
        return embedded?.let { LyricsSource.EMBEDDED to it }
    }

    private suspend fun driveText(fileId: String): String? {
        driveLrc.get(fileId)?.let { return it }
        return try {
            api.downloadText(fileId).takeIf { it.isNotBlank() }?.also { driveLrc.put(fileId, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Fichier .lrc $fileId illisible, source suivante", e)
            null
        }
    }

    /**
     * Requêtes successives (Namida) : titre + artiste, + album, puis avec le premier artiste seul.
     * Renvoie (instrumental, texte) ou null si rien de fiable.
     */
    private suspend fun fetchOnline(t: TrackEntity): Pair<Boolean, String?>? {
        val artist = t.artist.takeUnless { it == DriveMusicScanner.UNKNOWN_ARTIST }.orEmpty()
        val album = t.album.takeUnless { it == DriveMusicScanner.UNKNOWN_ALBUM }
        val attempts = listOf(
            artist to null, artist to album,
            LyricsMatcher.firstArtist(artist) to null, LyricsMatcher.firstArtist(artist) to album,
        ).map { (a, al) -> LyricsMatcher.cleanArtist(a) to al }.distinct()
        for ((a, al) in attempts) {
            val results = lrcLib.search(title = t.title, artist = a, album = al)
            val best = LyricsMatcher.best(t.title, artist, t.durationMs, results) ?: continue
            if (LyricsMatcher.isInstrumental(t.title, best)) return true to null
            best.candidate.text?.let { return false to it }
        }
        return null
    }

    private fun recentlyNotFound(trackId: String): Boolean =
        notFoundAt[trackId]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true

    override suspend fun options(trackId: String, query: String?): List<LyricsOption> {
        val t = tracks.byId(trackId) ?: return emptyList()
        fun local(source: LyricsSource, text: String) = LyricsOption(
            source,
            LyricsCandidate(t.title, t.artist, t.album, t.durationMs, false,
                syncedLyrics = text.takeIf(LrcParser::isSynced), plainLyrics = text.takeUnless(LrcParser::isSynced)),
        )
        val locals = buildList {
            t.lrcFileId?.let { id -> driveText(id)?.let { add(local(LyricsSource.DRIVE_LRC, it)) } }
            t.embeddedLyrics?.takeIf { it.isNotBlank() }?.let { add(local(LyricsSource.EMBEDDED, it)) }
        }
        val online = lrcLib.search(query = query?.takeIf { it.isNotBlank() } ?: defaultQuery(trackId))
            .filter { it.text != null }
            .sortedWith(
                compareByDescending<LyricsCandidate> { LyricsMatcher.score(t.title, t.artist, it.title, it.artist) >= LyricsMatcher.MIN_SCORE }
                    .thenByDescending { it.synced }
                    .thenBy { c -> c.durationMs?.let { kotlin.math.abs(it - t.durationMs) } ?: Long.MAX_VALUE },
            )
            .map { LyricsOption(LyricsSource.ONLINE, it) }
        return locals + online
    }

    override suspend fun defaultQuery(trackId: String): String {
        val t = tracks.byId(trackId) ?: return ""
        val artist = t.artist.takeUnless { it == DriveMusicScanner.UNKNOWN_ARTIST }?.let(LyricsMatcher::firstArtist).orEmpty()
        return "${t.title} $artist".trim()
    }

    override suspend fun choose(trackId: String, text: String) = edit(trackId) { it.copy(manualText = text, ignored = false) }

    override suspend fun ignore(trackId: String) = edit(trackId) { it.copy(manualText = null, ignored = true) }

    override suspend fun resetToAuto(trackId: String) {
        notFoundAt.remove(trackId)
        edit(trackId) { it.copy(manualText = null, ignored = false, onlineText = null, onlineInstrumental = false, onlineFetchedAt = null) }
    }

    override suspend fun setOffset(trackId: String, offsetMs: Long) = edit(trackId) { it.copy(offsetMs = offsetMs) }

    private suspend fun edit(trackId: String, change: (LyricsEntity) -> LyricsEntity) {
        dao.upsert(change(dao.get(trackId) ?: LyricsEntity(trackId)))
    }

    private companion object {
        const val TAG = "Lyrics"
        const val RETRY_AFTER_MS = 6 * 60 * 60 * 1000L
    }
}
