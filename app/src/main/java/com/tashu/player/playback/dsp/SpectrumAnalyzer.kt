package com.tashu.player.playback.dsp

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectre du son joué par l'app, pour le visualiseur. Pas de permission micro : les échantillons
 * viennent directement de la chaîne audio d'ExoPlayer ([SpectrumTapAudioProcessor]).
 * Le calcul (FFT 1024 points, fenêtre de Hann, [BAND_COUNT] bandes logarithmiques) ne tourne que
 * quand [active] est vrai, c'est-à-dire quand le visualiseur est à l'écran.
 */
@Singleton
class SpectrumAnalyzer @Inject constructor() {
    @Volatile var active: Boolean = false

    /** Dernières valeurs (0..1) par bande ; lues par l'UI à chaque image. */
    @Volatile var bands: FloatArray = FloatArray(BAND_COUNT)
        private set
    @Volatile var updatedAt: Long = 0L
        private set

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val ring = FloatArray(N)
    private var filled = 0
    private var sampleRate = 44_100
    private var bandEdges = IntArray(BAND_COUNT + 1)

    fun configure(rate: Int) {
        sampleRate = rate.coerceAtLeast(8_000)
        val lo = 40.0
        val hi = minOf(16_000.0, sampleRate / 2.0 - 1)
        bandEdges = IntArray(BAND_COUNT + 1) { i ->
            val f = lo * (hi / lo).pow(i.toDouble() / BAND_COUNT)
            (f * N / sampleRate).toInt().coerceIn(1, N / 2 - 1)
        }
        filled = 0
    }

    /** Appelé depuis le thread audio avec un échantillon mono (-1..1). */
    fun push(sample: Float) {
        ring[filled++] = sample
        if (filled == N) {
            analyse()
            // Recouvrement de 50 % : environ 2 analyses par bloc de 1024 échantillons.
            System.arraycopy(ring, N / 2, ring, 0, N / 2)
            filled = N / 2
        }
    }

    private fun analyse() {
        for (i in 0 until N) { re[i] = ring[i] * window[i]; im[i] = 0f }
        fft(re, im)
        val out = FloatArray(BAND_COUNT)
        for (b in 0 until BAND_COUNT) {
            val from = bandEdges[b]
            val to = maxOf(bandEdges[b + 1], from + 1)
            var peak = 0f
            for (k in from until to) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            // Normalisation : magnitude -> dB -> 0..1 sur une plage de 60 dB.
            val db = 20f * log10((peak / (N / 4f)).coerceAtLeast(1e-6f))
            out[b] = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
        bands = out
        updatedAt = SystemClock.uptimeMillis()
    }

    /** FFT radix-2 en place. */
    private fun fft(xr: FloatArray, xi: FloatArray) {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = xr[i]; xr[i] = xr[j]; xr[j] = tr
                val ti = xi[i]; xi[i] = xi[j]; xi[j] = ti
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val vr = xr[b] * cr - xi[b] * ci
                    val vi = xr[b] * ci + xi[b] * cr
                    xr[b] = xr[a] - vr; xi[b] = xi[a] - vi
                    xr[a] += vr; xi[a] += vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val N = 1024
        const val BAND_COUNT = 48
    }
}

/** Processeur transparent : recopie l'audio tel quel et en donne une copie mono à l'analyseur. */
class SpectrumTapAudioProcessor(private val analyzer: SpectrumAnalyzer) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        analyzer.configure(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (analyzer.active) {
            val view = inputBuffer.duplicate().order(inputBuffer.order())
            val channels = inputAudioFormat.channelCount
            val pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
            while (view.remaining() >= channels * (if (pcm16) 2 else 4)) {
                var sum = 0f
                repeat(channels) { sum += if (pcm16) view.short / 32768f else view.float }
                analyzer.push(sum / channels)
            }
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }
}
