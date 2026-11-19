package com.tashu.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tashu.player.domain.model.ReplayGainInfo
import com.tashu.player.domain.model.Track
import com.tashu.player.playback.datasource.GoogleDriveDataSource
import java.io.File

object MediaItemMapper {
    const val EXTRA_ALBUM_ID = "albumId"
    const val EXTRA_CODEC = "codec"
    const val EXTRA_SAMPLE_RATE = "sampleRate"
    const val EXTRA_BIT_DEPTH = "bitDepth"
    const val EXTRA_BITRATE = "bitrate"
    const val EXTRA_RG_TRACK = "rgTrack"
    const val EXTRA_RG_TRACK_PEAK = "rgTrackPeak"
    const val EXTRA_RG_ALBUM = "rgAlbum"
    const val EXTRA_RG_ALBUM_PEAK = "rgAlbumPeak"
    const val EXTRA_HAS_LYRICS = "hasLyrics"

    fun toMediaItem(t: Track): MediaItem {
        val extras = Bundle().apply {
            putLong(EXTRA_ALBUM_ID, t.albumId)
            putString(EXTRA_CODEC, t.codec.label)
            t.sampleRateHz?.let { putInt(EXTRA_SAMPLE_RATE, it) }
            t.bitDepth?.let { putInt(EXTRA_BIT_DEPTH, it) }
            t.bitrateKbps?.let { putInt(EXTRA_BITRATE, it) }
            t.replayGain.trackGainDb?.let { putFloat(EXTRA_RG_TRACK, it) }
            t.replayGain.trackPeak?.let { putFloat(EXTRA_RG_TRACK_PEAK, it) }
            t.replayGain.albumGainDb?.let { putFloat(EXTRA_RG_ALBUM, it) }
            t.replayGain.albumPeak?.let { putFloat(EXTRA_RG_ALBUM_PEAK, it) }
            putBoolean(EXTRA_HAS_LYRICS, t.hasLyrics)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
            .setAlbumArtist(t.albumArtist)
            .setGenre(t.genre)
            .setComposer(t.composer)
            .setTrackNumber(t.trackNumber)
            .setDiscNumber(t.discNumber)
            .setRecordingYear(t.year)
            .setDurationMs(t.durationMs)
            .setArtworkUri(t.artworkPath?.let { Uri.fromFile(File(it)) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(GoogleDriveDataSource.uriFor(t.id))
            .setCustomCacheKey(t.id)
            .setMimeType(t.mimeType.takeIf { it.startsWith("audio/") })
            .setMediaMetadata(metadata)
            .build()
    }

    fun replayGainOf(item: MediaItem?): ReplayGainInfo? {
        val e = item?.mediaMetadata?.extras ?: return null
        fun f(k: String) = if (e.containsKey(k)) e.getFloat(k) else null
        return ReplayGainInfo(f(EXTRA_RG_TRACK), f(EXTRA_RG_TRACK_PEAK), f(EXTRA_RG_ALBUM), f(EXTRA_RG_ALBUM_PEAK))
    }

    fun albumIdOf(item: MediaItem?): Long? =
        item?.mediaMetadata?.extras?.takeIf { it.containsKey(EXTRA_ALBUM_ID) }?.getLong(EXTRA_ALBUM_ID)

    fun browsable(id: String, title: String, subtitle: String? = null, artwork: String? = null, type: Int): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                .setArtworkUri(artwork?.let { Uri.fromFile(File(it)) })
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build()
        ).build()
}
