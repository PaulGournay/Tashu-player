package com.tashu.player.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.tashu.player.data.db.PlayEventEntity
import com.tashu.player.data.db.PlayHistoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Historique d'écoute : une ligne par passage d'un morceau, avec le temps réellement joué, mesuré à l'horloge
 * pendant que le lecteur joue (pauses, chargements et sauts dans le morceau ne comptent pas). Le fondu
 * enchaîné ne superpose jamais deux pistes ([CrossfadeController]) : aucun temps n'est compté deux fois.
 *
 * Écrit dès 1 s d'écoute, puis toutes les 10 s, à la pause, au changement de morceau et à l'arrêt du service :
 * si l'app est tuée en pleine lecture, au plus 10 s sont perdues. Appels sur le main thread (accès Player).
 */
class ListenTracker(
    private val player: Player,
    private val dao: PlayHistoryDao,
    private val scope: CoroutineScope,
) : Player.Listener {

    private class Session(val trackId: String, val albumId: Long?, val artist: String, val durationMs: Long) {
        var startedAt: Long? = null
        var listenedMs = 0L
        var playingSince: Long? = null
        /** Ligne en base ; lue et écrite seulement sur [writer]. */
        var rowId: Long? = null
    }

    private var current: Session? = null
    private var ticker: Job? = null
    // Un seul fil d'écriture : l'insertion d'une écoute passe toujours avant ses mises à jour.
    private val writer = Executors.newSingleThreadExecutor()

    fun start() {
        player.addListener(this)
        open(player.currentMediaItem)
        ticker = scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                current?.takeIf { it.playingSince != null }?.let(::save)
            }
        }
    }

    /** Arrêt du service : dernière écriture, attendue au plus 1 s. */
    fun stop() {
        ticker?.cancel()
        player.removeListener(this)
        close()
        writer.shutdown()
        writer.awaitTermination(1, TimeUnit.SECONDS)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        close()
        open(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val s = current ?: return
        if (isPlaying) {
            if (s.startedAt == null) s.startedAt = System.currentTimeMillis()
            s.playingSince = SystemClock.elapsedRealtime()
        } else {
            settle(s)
            save(s)
        }
    }

    private fun open(item: MediaItem?) {
        current = item?.let {
            val m = it.mediaMetadata
            Session(
                trackId = it.mediaId,
                albumId = MediaItemMapper.albumIdOf(it)?.takeIf { id -> id >= 0 },
                artist = checkNotNull(m.artist).toString(),
                durationMs = checkNotNull(m.durationMs),
            )
        }
        if (player.isPlaying) onIsPlayingChanged(true)
    }

    private fun close() {
        val s = current ?: return
        settle(s)
        save(s)
        current = null
    }

    /** Ajoute le temps joué depuis la dernière reprise. */
    private fun settle(s: Session) {
        val since = s.playingSince ?: return
        val now = SystemClock.elapsedRealtime()
        s.listenedMs += now - since
        s.playingSince = null
    }

    private fun save(s: Session) {
        val listened = s.listenedMs + (s.playingSince?.let { SystemClock.elapsedRealtime() - it } ?: 0)
        val startedAt = s.startedAt ?: return
        writer.execute {
            val id = s.rowId
            when {
                id != null -> dao.setListened(id, listened)
                listened >= MIN_SAVED_MS -> s.rowId = dao.insert(PlayEventEntity(0, s.trackId, s.albumId, s.artist, startedAt, listened, s.durationMs))
            }
        }
    }

    private companion object {
        const val FLUSH_MS = 10_000L
        const val MIN_SAVED_MS = 1_000L
    }
}
