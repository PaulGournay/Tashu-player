package com.tashu.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.tashu.player.domain.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class AudioInfo(
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val decoder: String? = null,
    val outputSampleRateHz: Int? = null,
    val outputDevice: String? = null,
)

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val metadata: MediaMetadata = MediaMetadata.EMPTY,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val audio: AudioInfo = AudioInfo(),
    val queue: List<MediaItem> = emptyList(),
    val queueIndex: Int = 0,
)

/** Pont UI ↔ [MusicPlaybackService] via `MediaController` (même API que Auto/Wear/Bluetooth). */
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    fun connect() {
        if (controller != null) return
        scope.launch {
            val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
            val c = MediaController.Builder(context, token)
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) = publishExtras(extras)
                })
                .buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = snapshot(player)
            })
            publishExtras(c.sessionExtras)
            snapshot(c)
            // Position : polling léger uniquement pendant la lecture.
            while (isActive) {
                if (c.isPlaying) _state.update { it.copy(positionMs = c.currentPosition) }
                delay(if (c.isPlaying) 250 else 1000)
            }
        }
    }

    private fun snapshot(p: Player) {
        val item = p.currentMediaItem
        val extras = item?.mediaMetadata?.extras
        _state.update { s ->
            s.copy(
                connected = true,
                mediaId = item?.mediaId,
                metadata = p.mediaMetadata,
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition,
                durationMs = p.duration.coerceAtLeast(0),
                shuffle = p.shuffleModeEnabled,
                repeatMode = p.repeatMode,
                queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
                queueIndex = p.currentMediaItemIndex,
                // Valeurs indexées (Room) en attendant le format réel remonté par le décodeur.
                audio = s.audio.copy(
                    codec = extras?.getString(MediaItemMapper.EXTRA_CODEC),
                    bitDepth = extras?.getInt(MediaItemMapper.EXTRA_BIT_DEPTH)?.takeIf { it > 0 },
                ),
            )
        }
    }

    private fun publishExtras(e: Bundle) {
        _state.update { s ->
            s.copy(
                audio = s.audio.copy(
                    sampleRateHz = e.getInt(MusicPlaybackService.KEY_SAMPLE_RATE).takeIf { it > 0 },
                    channels = e.getInt(MusicPlaybackService.KEY_CHANNELS).takeIf { it > 0 },
                    bitrateKbps = e.getInt(MusicPlaybackService.KEY_BITRATE).takeIf { it > 0 }?.div(1000),
                    decoder = e.getString(MusicPlaybackService.KEY_DECODER),
                    outputSampleRateHz = e.getInt(MusicPlaybackService.KEY_OUTPUT_SAMPLE_RATE).takeIf { it > 0 },
                    outputDevice = e.getString(MusicPlaybackService.KEY_OUTPUT_DEVICE),
                )
            )
        }
    }

    // ---------------- Actions ----------------

    fun play(tracks: List<Track>, startIndex: Int = 0, queueName: String? = null) {
        val c = controller ?: return
        if (queueName != null) {
            // Nouvelle file nommée : l'ancienne file reste sauvegardée et rebasculable.
            c.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_NEW_QUEUE, Bundle.EMPTY),
                Bundle().apply {
                    putString(MusicPlaybackService.ARG_NAME, queueName)
                    putStringArrayList(MusicPlaybackService.ARG_IDS, ArrayList(tracks.map { it.id }))
                    putInt(MusicPlaybackService.ARG_INDEX, startIndex)
                },
            )
            return
        }
        c.setMediaItems(tracks.map(MediaItemMapper::toMediaItem), startIndex, 0)
        c.prepare()
        c.play()
    }

    /** Lecture aléatoire : départ sur un morceau au hasard, mode aléatoire activé. */
    fun playShuffled(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.setMediaItems(tracks.map(MediaItemMapper::toMediaItem), tracks.indices.random(), 0)
        c.shuffleModeEnabled = true
        c.prepare()
        c.play()
    }

    /** Ajoute à la file : juste après le morceau en cours ([next]) ou à la fin. File vide : lance la lecture. */
    fun enqueue(tracks: List<Track>, next: Boolean) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) { play(tracks); return }
        val index = if (next) c.currentMediaItemIndex + 1 else c.mediaItemCount
        c.addMediaItems(index, tracks.map(MediaItemMapper::toMediaItem))
    }

    fun removeFromQueue(index: Int) = controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }

    fun switchQueue(queueId: Long) {
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CMD_SWITCH_QUEUE, Bundle.EMPTY),
            Bundle().apply { putLong(MusicPlaybackService.ARG_QUEUE_ID, queueId) },
        )
    }

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun skipTo(index: Int) = controller?.seekTo(index, 0)
    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
