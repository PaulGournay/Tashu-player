package com.tashu.player.playback.dsp

import android.os.SystemClock
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pulsation de la musique jouée par l'app, pour le nuage vivant. Pas de permission : les échantillons
 * viennent de [SpectrumTapAudioProcessor], la position lue de l'audio du sink ([PulseAudioSink]).
 *
 * Tous les 20 ms d'audio : énergie des graves (passe-bas à 150 Hz) et énergie totale, rapportées au
 * niveau moyen des dernières secondes (indépendant du volume du morceau), plus un bonus d'attaque
 * quand les graves montent d'un coup. Enveloppe : montée rapide, retombée en ~160 ms.
 *
 * L'audio est traité 250 à 750 ms avant d'être entendu (tampon de l'AudioTrack) : chaque valeur est
 * datée en temps média et l'UI lit celle qui correspond à la position réellement jouée.
 * Écrit par le thread de lecture, lu par l'UI ; ni verrou ni allocation par tampon.
 */
@Singleton
class MusicPulse @Inject constructor() {
    private val users = AtomicInteger()

    /** Vrai tant qu'au moins un nuage réactif est affiché : le calcul ne tourne qu'à ce moment-là. */
    val active: Boolean get() = users.get() > 0
    fun acquire() { users.incrementAndGet() }
    fun release() { users.decrementAndGet() }

    @Volatile private var playing = false
    @Volatile private var positionUs = NO_POSITION
    @Volatile private var positionAtMs = 0L

    private val times = LongArray(RING)
    private val values = FloatArray(RING)
    @Volatile private var written = 0

    private var sampleRate = 44_100
    private var blockFrames = 882
    private var lowPass = 0f
    private var lowPassCoef = 0f
    private var bassSq = 0f
    private var allSq = 0f
    private var inBlock = 0
    private var framesSeen = 0L
    private var anchorUs = 0L
    private var anchorFrames = 0L
    private var lastBufferUs = NO_POSITION
    private var average = 0f
    private var baseline = 0f
    private var envelope = 0f

    fun configure(rate: Int) {
        sampleRate = rate.coerceAtLeast(8_000)
        blockFrames = sampleRate / 50
        lowPassCoef = 1f - exp(-2f * Math.PI.toFloat() * 150f / sampleRate)
        restart()
    }

    /** Sink : nouveau tampon d'entrée, à [presentationUs]. */
    fun onBuffer(presentationUs: Long) {
        if (presentationUs == lastBufferUs) return
        lastBufferUs = presentationUs
        anchorUs = presentationUs
        anchorFrames = framesSeen
    }

    /** Sink : position de l'audio réellement joué. */
    fun onPosition(us: Long, nowMs: Long) {
        positionAtMs = nowMs
        positionUs = us
    }

    fun onPlaying(value: Boolean) { playing = value }

    /** Sink vidé (seek, changement de piste non enchaîné) : l'historique ne correspond plus. */
    fun onFlush() {
        playing = false
        positionUs = NO_POSITION
        restart()
    }

    private fun restart() {
        lowPass = 0f; bassSq = 0f; allSq = 0f; inBlock = 0
        framesSeen = 0L; anchorFrames = 0L; lastBufferUs = NO_POSITION
        envelope = 0f
        written = 0
    }

    /** Processeur : un échantillon mono (-1..1). */
    fun push(sample: Float) {
        lowPass += lowPassCoef * (sample - lowPass)
        bassSq += lowPass * lowPass
        allSq += sample * sample
        framesSeen++
        if (++inBlock == blockFrames) endBlock()
    }

    private fun endBlock() {
        val bass = sqrt(bassSq / inBlock)
        val all = sqrt(allSq / inBlock)
        val energy = bass * 0.7f + all * 0.3f
        average += (energy - average) * 0.01f
        val loud = (energy / max(average * 1.8f, 0.005f)).coerceIn(0f, 1f)
        val onset = ((bass - baseline * 1.3f) / max(baseline, 0.005f)).coerceIn(0f, 1f)
        baseline += (bass - baseline) * 0.07f
        val v = (0.5f * loud + 0.7f * onset).coerceIn(0f, 1f)
        envelope += (v - envelope) * (if (v > envelope) 0.6f else 0.12f)

        val mid = framesSeen - inBlock / 2 - anchorFrames
        val k = written and (RING - 1)
        times[k] = anchorUs + mid * 1_000_000L / sampleRate
        values[k] = envelope
        written++
        bassSq = 0f; allSq = 0f; inBlock = 0
    }

    /** Niveau 0..1 de la musique à l'instant entendu ; 0 en pause ou sans lecture. */
    fun level(nowMs: Long): Float {
        if (!playing) return 0f
        val pos = positionUs
        val elapsed = nowMs - positionAtMs
        if (pos == NO_POSITION || elapsed > 250) return 0f
        val target = pos + elapsed * 1000
        val count = written
        var i = count - 1
        val stop = max(0, count - RING)
        while (i >= stop) {
            val k = i and (RING - 1)
            val t = times[k]
            if (t <= target) return if (target - t < 100_000) values[k] else 0f
            i--
        }
        return 0f
    }

    private companion object {
        const val RING = 256
        const val NO_POSITION = Long.MIN_VALUE
    }
}

/** Sink qui renseigne [MusicPulse] : date des tampons reçus, position jouée, lecture ou pause. */
class PulseAudioSink(sink: AudioSink, private val pulse: MusicPulse) : ForwardingAudioSink(sink) {

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        pulse.onBuffer(presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val us = super.getCurrentPositionUs(sourceEnded)
        if (us != AudioSink.CURRENT_POSITION_NOT_SET) pulse.onPosition(us, SystemClock.uptimeMillis())
        return us
    }

    override fun play() {
        super.play()
        pulse.onPlaying(true)
    }

    override fun pause() {
        pulse.onPlaying(false)
        super.pause()
    }

    override fun flush() {
        pulse.onFlush()
        super.flush()
    }
}
