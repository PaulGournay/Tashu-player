package com.tashu.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.tashu.player.data.db.MusicDatabase
import com.tashu.player.domain.repository.LibraryRepository
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.playback.cache.OfflineManager
import com.tashu.player.playback.cache.PlaybackFactory
import com.tashu.player.playback.dsp.AudioEffectsController
import com.tashu.player.playback.dsp.ParametricEqAudioProcessor
import com.tashu.player.playback.dsp.SpectrumAnalyzer
import com.tashu.player.playback.dsp.SpectrumTapAudioProcessor
import com.tashu.player.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Service de lecture : ExoPlayer + MediaLibrarySession (notification média, écran verrouillé,
 * Bluetooth, Android Auto, Wear OS).
 *
 *  - **Gapless** : une seule playlist ExoPlayer ; les délais encodeur (LAME/iTunSMPB, FLAC, Opus
 *    pre-skip) sont retirés nativement, aucune ré-préparation entre pistes.
 *  - **ReplayGain / GEQ / limiteur** : [AudioEffectsController] (DynamicsProcessing sur la session).
 *  - **PEQ 10 bandes** : [ParametricEqAudioProcessor] dans le DefaultAudioSink.
 *  - **Streaming Drive** : DataSource `gdrive://` → cache hors-ligne → cache LRU → Drive (Bearer + Range).
 *  - **Prefetch** de la piste suivante, **files multiples**, **crossfade intelligent**, **DAC USB**.
 */
@AndroidEntryPoint
class MusicPlaybackService : MediaLibraryService() {

    @Inject @PlaybackFactory lateinit var dataSourceFactory: DataSource.Factory
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var offline: OfflineManager
    @Inject lateinit var db: MusicDatabase
    @Inject lateinit var json: Json
    @Inject lateinit var spectrum: SpectrumAnalyzer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var effects: AudioEffectsController
    private lateinit var crossfade: CrossfadeController
    private lateinit var queues: QueueManager
    private lateinit var usbRouter: UsbDacRouter
    private val peq = ParametricEqAudioProcessor()
    private val audioInfo = Bundle()

    override fun onCreate() {
        super.onCreate()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(peq, SpectrumTapAudioProcessor(spectrum)))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
            // Utilise les décodeurs d'extension (FFmpeg : ALAC, etc.) s'ils sont présents dans l'APK.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Tampons plus généreux qu'en vidéo : réseau mobile + fichiers lossless volumineux.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        // Session audio fixe → DynamicsProcessing attaché une seule fois.
        val audioSessionId = Util.generateAudioSessionIdV21(this)
        player.audioSessionId = audioSessionId
        effects = AudioEffectsController(peq).apply { attach(audioSessionId) }

        crossfade = CrossfadeController(player, scope).apply { start() }
        queues = QueueManager(player, db.queueDao(), library, json)
        usbRouter = UsbDacRouter(this, player) { publishOutputDevice(it) }.apply { start() }

        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

        scope.launch {
            settings.audioSettings.collect { s ->
                effects.update(s)
                crossfade.durationMs = s.crossfadeMs
                usbRouter.enabled = s.preferUsbDac
                effects.onTrackChanged(MediaItemMapper.replayGainOf(player.currentMediaItem), isAlbumContext())
            }
        }
        scope.launch { queues.restoreActive(playWhenReady = false) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Sauvegarde synchrone : le scope est annulé juste après.
        runBlocking { withTimeoutOrNull(1_000) { queues.saveCurrent() } }
        crossfade.stop()
        usbRouter.stop()
        effects.release()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Player events

    private val playerListener = object : Player.Listener {
        private var previous: MediaItem? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            effects.onTrackChanged(MediaItemMapper.replayGainOf(mediaItem), isAlbumContext())
            crossfade.onTransition(previous, mediaItem)
            previous = mediaItem
            mediaItem?.mediaId?.let { id -> scope.launch { library.markPlayed(id) } }
            prefetchNext()
            scope.launch { queues.saveCurrent() }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) scope.launch { queues.saveCurrent() } // position exacte mémorisée
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = prefetchNext()
    }

    /** Smart ReplayGain : mode album si la piste voisine appartient au même album. */
    private fun isAlbumContext(): Boolean {
        val cur = MediaItemMapper.albumIdOf(player.currentMediaItem) ?: return false
        val prev = if (player.hasPreviousMediaItem()) MediaItemMapper.albumIdOf(player.getMediaItemAt(player.previousMediaItemIndex)) else null
        val next = if (player.hasNextMediaItem()) MediaItemMapper.albumIdOf(player.getMediaItemAt(player.nextMediaItemIndex)) else null
        return cur == prev || cur == next
    }

    private fun prefetchNext() {
        if (!player.hasNextMediaItem()) return
        offline.prefetch(player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
    }

    /** Codec / fréquence / profondeur réelles → extras de session → UI "Now Playing". */
    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            audioInfo.putString(KEY_MIME, format.sampleMimeType)
            audioInfo.putInt(KEY_SAMPLE_RATE, format.sampleRate)
            audioInfo.putInt(KEY_CHANNELS, format.channelCount)
            audioInfo.putInt(KEY_BITRATE, if (format.bitrate != Format.NO_VALUE) format.bitrate else format.averageBitrate)
            audioInfo.putInt(KEY_PCM_ENCODING, format.pcmEncoding)
            session.setSessionExtras(Bundle(audioInfo))
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioInfo.putString(KEY_DECODER, decoderName)
            session.setSessionExtras(Bundle(audioInfo))
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            audioInfo.putInt(KEY_OUTPUT_SAMPLE_RATE, audioTrackConfig.sampleRate)
            audioInfo.putInt(KEY_OUTPUT_ENCODING, audioTrackConfig.encoding)
            audioInfo.putBoolean(KEY_OFFLOAD, audioTrackConfig.offload)
            session.setSessionExtras(Bundle(audioInfo))
        }
    }

    private fun publishOutputDevice(device: AudioDeviceInfo?) {
        audioInfo.putString(KEY_OUTPUT_DEVICE, device?.productName?.toString())
        audioInfo.putIntArray(KEY_DEVICE_RATES, device?.sampleRates ?: IntArray(0))
        if (::session.isInitialized) session.setSessionExtras(Bundle(audioInfo))
    }

    // ------------------------------------------------------------------ Library / Auto

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SWITCH_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEW_QUEUE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> = scope.future {
            when (customCommand.customAction) {
                CMD_SWITCH_QUEUE -> queues.switchTo(args.getLong(ARG_QUEUE_ID))
                CMD_NEW_QUEUE -> queues.createAndPlay(
                    args.getString(ARG_NAME) ?: "File",
                    args.getStringArrayList(ARG_IDS).orEmpty(),
                    args.getInt(ARG_INDEX),
                )
            }
            SessionResult(SessionResult.RESULT_SUCCESS)
        }

        /** Les contrôleurs envoient des MediaItems sans URI : on les résout depuis Room. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            library.tracks(mediaItems.map { it.mediaId }).map(MediaItemMapper::toMediaItem).toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            queues.restoreActive(playWhenReady = false)
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            LibraryResult.ofItem(MediaItemMapper.browsable(ROOT, "Tashu", type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            library.track(mediaId)?.let { LibraryResult.ofItem(MediaItemMapper.toMediaItem(it), null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    MediaItemMapper.browsable(NODE_ALBUMS, "Albums", type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    MediaItemMapper.browsable(NODE_TRACKS, "Morceaux", type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                )
                parentId == NODE_ALBUMS -> library.albums().first().map {
                    MediaItemMapper.browsable("$PREFIX_ALBUM${it.id}", it.title, it.artist, it.artworkPath, MediaMetadata.MEDIA_TYPE_ALBUM)
                }
                parentId == NODE_TRACKS -> library.tracks().first().map(MediaItemMapper::toMediaItem)
                parentId.startsWith(PREFIX_ALBUM) ->
                    library.albumTracks(parentId.removePrefix(PREFIX_ALBUM).toLong()).first().map(MediaItemMapper::toMediaItem)
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
        }
    }

    companion object {
        const val CMD_SWITCH_QUEUE = "tashu.SWITCH_QUEUE"
        const val CMD_NEW_QUEUE = "tashu.NEW_QUEUE"
        const val ARG_QUEUE_ID = "queueId"
        const val ARG_NAME = "name"
        const val ARG_IDS = "ids"
        const val ARG_INDEX = "index"

        const val KEY_MIME = "mime"
        const val KEY_SAMPLE_RATE = "sampleRate"
        const val KEY_CHANNELS = "channels"
        const val KEY_BITRATE = "bitrate"
        const val KEY_PCM_ENCODING = "pcmEncoding"
        const val KEY_DECODER = "decoder"
        const val KEY_OUTPUT_SAMPLE_RATE = "outSampleRate"
        const val KEY_OUTPUT_ENCODING = "outEncoding"
        const val KEY_OFFLOAD = "offload"
        const val KEY_OUTPUT_DEVICE = "outDevice"
        const val KEY_DEVICE_RATES = "deviceRates"

        private const val ROOT = "root"
        private const val NODE_ALBUMS = "albums"
        private const val NODE_TRACKS = "tracks"
        private const val PREFIX_ALBUM = "album:"
    }
}
