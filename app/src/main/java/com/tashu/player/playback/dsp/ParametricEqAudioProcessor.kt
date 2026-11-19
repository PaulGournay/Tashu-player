package com.tashu.player.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import com.tashu.player.domain.model.EqBandSetting
import com.tashu.player.domain.model.PeqFilterType
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Égaliseur **paramétrique** 10 bandes (biquads RBJ, Transposed Direct Form II, calcul en double)
 * inséré dans la chaîne `DefaultAudioSink` d'ExoPlayer — fréquence, gain ET facteur Q par bande,
 * ce que `DynamicsProcessing` (bandes à fréquences de coupure fixes) ne permet pas.
 *
 * Accepte PCM 16 bits et PCM float. Les réglages sont échangés de façon thread-safe et
 * appliqués au prochain buffer, sans reconfiguration du sink (donc sans coupure gapless).
 */
class ParametricEqAudioProcessor : BaseAudioProcessor() {

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    @Volatile private var pendingBands: List<EqBandSetting>? = null
    @Volatile private var pendingPreampDb: Float = 0f
    @Volatile var enabled: Boolean = false

    private var filters: Array<Biquad> = emptyArray()
    private var state = DoubleArray(0) // [canal][filtre][z1,z2]
    private var preampLinear = 1.0
    private var bands: List<EqBandSetting> = emptyList()

    fun update(bands: List<EqBandSetting>, preampDb: Float, enabled: Boolean) {
        pendingPreampDb = preampDb
        pendingBands = bands
        this.enabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingBands = pendingBands ?: bands // force le recalcul pour la nouvelle fréquence d'échantillonnage
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        applyPending()

        if (!enabled || (filters.isEmpty() && preampLinear == 1.0)) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        val nf = filters.size
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            var ch = 0
            while (inputBuffer.hasRemaining()) {
                val y = process(inputBuffer.short / 32768.0, ch, nf)
                out.putShort((y * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                ch = if (ch + 1 == channels) 0 else ch + 1
            }
        } else {
            var ch = 0
            while (inputBuffer.hasRemaining()) {
                out.putFloat(process(inputBuffer.float.toDouble(), ch, nf).toFloat())
                ch = if (ch + 1 == channels) 0 else ch + 1
            }
        }
        out.flip()
    }

    private fun process(input: Double, ch: Int, nf: Int): Double {
        var x = input * preampLinear
        var s = ch * nf * 2
        for (f in filters) {
            val y = f.b0 * x + state[s]
            state[s] = f.b1 * x - f.a1 * y + state[s + 1]
            state[s + 1] = f.b2 * x - f.a2 * y
            x = y
            s += 2
        }
        return x
    }

    private fun applyPending() {
        val newBands = pendingBands ?: return
        pendingBands = null
        bands = newBands
        preampLinear = 10.0.pow(pendingPreampDb / 20.0)
        val fs = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: return
        filters = newBands.filter { it.gainDb != 0f }.map { design(it, fs.toDouble()) }.toTypedArray()
        state = DoubleArray(inputAudioFormat.channelCount * filters.size * 2)
    }

    override fun onFlush() { state.fill(0.0) }

    override fun onReset() {
        filters = emptyArray(); state = DoubleArray(0); pendingBands = bands
    }

    /** Coefficients "Audio EQ Cookbook" (R. Bristow-Johnson). */
    private fun design(b: EqBandSetting, fs: Double): Biquad {
        val f0 = b.frequencyHz.toDouble().coerceIn(10.0, fs * 0.49)
        val a = 10.0.pow(b.gainDb / 40.0)
        val w0 = 2 * PI * f0 / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * b.q.toDouble().coerceAtLeast(0.1))
        val sa = 2 * sqrt(a) * alpha
        val (b0, b1, b2, a0, a1, a2) = when (b.type) {
            PeqFilterType.PEAKING -> Six(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            PeqFilterType.LOW_SHELF -> Six(
                a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
            )
            PeqFilterType.HIGH_SHELF -> Six(
                a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
            )
        }
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private data class Six(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double)
}
