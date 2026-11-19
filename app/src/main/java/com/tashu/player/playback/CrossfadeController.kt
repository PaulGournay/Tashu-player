package com.tashu.player.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Smart Crossfade" sur un seul ExoPlayer : fondu sortant en fin de piste puis fondu entrant.
 *
 * Smart = désactivé automatiquement quand deux pistes consécutives appartiennent au même album
 * (albums live / concept) pour préserver le **gapless**. Le fondu ne superpose pas les pistes :
 * un vrai crossfade superposé nécessite deux instances ExoPlayer mixées (évolution possible).
 */
class CrossfadeController(
    private val player: Player,
    private val scope: CoroutineScope,
) {
    @Volatile var durationMs: Int = 0
    private var loop: Job? = null
    private var fadeIn: Job? = null

    fun start() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (durationMs <= 0 || !player.isPlaying || fadeIn?.isActive == true) continue
                if (!player.hasNextMediaItem() || sameAlbum(player.currentMediaItem, player.getMediaItemAt(player.nextMediaItemIndex))) {
                    player.volume = 1f; continue
                }
                val remaining = player.duration - player.currentPosition
                if (player.duration > 0 && remaining in 0..durationMs.toLong()) {
                    player.volume = (remaining.toFloat() / durationMs).coerceIn(0f, 1f)
                }
            }
        }
    }

    fun onTransition(previous: MediaItem?, current: MediaItem?) {
        if (durationMs <= 0 || sameAlbum(previous, current)) { player.volume = 1f; return }
        fadeIn?.cancel()
        fadeIn = scope.launch {
            val steps = (durationMs / TICK_MS).coerceAtLeast(1)
            for (i in 1..steps) {
                player.volume = i.toFloat() / steps
                delay(TICK_MS)
            }
            player.volume = 1f
        }
    }

    fun stop() { loop?.cancel(); fadeIn?.cancel(); player.volume = 1f }

    private fun sameAlbum(a: MediaItem?, b: MediaItem?): Boolean {
        val x = MediaItemMapper.albumIdOf(a) ?: return false
        return x == MediaItemMapper.albumIdOf(b)
    }

    private companion object { const val TICK_MS = 50L }
}
