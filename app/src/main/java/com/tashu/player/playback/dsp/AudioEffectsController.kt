package com.tashu.player.playback.dsp

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import com.tashu.player.domain.model.AudioSettings
import com.tashu.player.domain.model.ReplayGainInfo
import com.tashu.player.domain.model.ReplayGainMode
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * DSP système attaché à la session audio d'ExoPlayer via [DynamicsProcessing] (API 28+) :
 *  - **GEQ 10 bandes** (Pre-EQ) ;
 *  - **ReplayGain** + préampli appliqués en gain d'entrée (gains positifs possibles) ;
 *  - **Limiteur** brickwall à -0,5 dBFS qui protège des écrêtages dus aux gains positifs / EQ.
 *
 * Le PEQ (avec facteur Q) est géré séparément par [ParametricEqAudioProcessor] dans le sink.
 */
class AudioEffectsController(private val peq: ParametricEqAudioProcessor) {

    private var dp: DynamicsProcessing? = null
    private var settings = AudioSettings()
    private var replayGainDb = 0f

    fun attach(audioSessionId: Int) {
        release()
        dp = runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ true, /* preEqBandCount = */ BANDS,
                /* mbcInUse = */ false, 0,
                /* postEqInUse = */ false, 0,
                /* limiterInUse = */ true,
            ).build()
            DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }
        }.onFailure { Log.e(TAG, "DynamicsProcessing indisponible", it) }.getOrNull()
        applyAll()
    }

    fun update(newSettings: AudioSettings) {
        settings = newSettings
        peq.update(newSettings.peqBands, if (newSettings.peqEnabled) newSettings.preampDb else 0f, newSettings.peqEnabled)
        applyAll()
    }

    /** Appelé à chaque transition de piste. */
    fun onTrackChanged(rg: ReplayGainInfo?, albumContext: Boolean) {
        replayGainDb = computeReplayGain(rg, albumContext)
        applyGain()
    }

    fun computeReplayGain(rg: ReplayGainInfo?, albumContext: Boolean): Float {
        val mode = settings.replayGainMode
        if (mode == ReplayGainMode.OFF) return 0f
        val useAlbum = mode == ReplayGainMode.ALBUM || (mode == ReplayGainMode.SMART && albumContext)
        val gain = (if (useAlbum) rg?.albumGainDb ?: rg?.trackGainDb else rg?.trackGainDb ?: rg?.albumGainDb)
            ?: return settings.replayGainFallbackDb
        val peak = if (useAlbum) rg?.albumPeak ?: rg?.trackPeak else rg?.trackPeak ?: rg?.albumPeak
        val total = gain + settings.replayGainPreampDb
        // Prévention d'écrêtage : gain max = -20·log10(peak).
        return if (peak != null && peak > 0f) min(total, (-20 * log10(peak.toDouble())).toFloat()) else total
    }

    private fun applyAll() {
        val d = dp ?: return
        runCatching {
            val eq = DynamicsProcessing.Eq(true, settings.geqEnabled, BANDS)
            for (i in 0 until BANDS) {
                eq.setBand(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], settings.geqGainsDb.getOrElse(i) { 0f }))
            }
            d.setPreEqAllChannelsTo(eq)
            d.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.5f, 0f)
            )
        }.onFailure { Log.e(TAG, "Application EQ échouée", it) }
        applyGain()
    }

    private fun applyGain() {
        val preamp = if (settings.geqEnabled && !settings.peqEnabled) settings.preampDb else 0f
        runCatching { dp?.setInputGainAllChannelsTo(replayGainDb + preamp) }
    }

    fun release() {
        runCatching { dp?.release() }
        dp = null
    }

    companion object {
        private const val TAG = "AudioEffects"
        private const val BANDS = 10
        /**
         * DynamicsProcessing définit chaque bande par sa fréquence de coupure HAUTE :
         * on prend la moyenne géométrique entre deux centres ISO (31 Hz … 16 kHz).
         */
        private val CUTOFFS: FloatArray = AudioSettings.GEQ_FREQUENCIES.let { c ->
            FloatArray(c.size) { i -> if (i == c.lastIndex) 20_000f else sqrt(c[i] * c[i + 1]) }
        }
    }
}
