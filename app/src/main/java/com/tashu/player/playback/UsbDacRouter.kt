package com.tashu.player.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer

/**
 * Détecte un DAC USB externe et y route explicitement la sortie d'ExoPlayer
 * (`setPreferredAudioDevice`). Expose le nom/les fréquences supportées pour l'UI.
 * Note : le mixer Android peut encore rééchantillonner ; le bit-perfect strict
 * (API 34+ `AudioMixerAttributes` / mode BIT_PERFECT) est un ajout possible.
 */
class UsbDacRouter(
    context: Context,
    private val player: ExoPlayer,
    private val onChanged: (AudioDeviceInfo?) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile var enabled = true
        set(value) { field = value; route() }
    var current: AudioDeviceInfo? = null
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = route()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = route()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        route()
    }

    fun stop() = audioManager.unregisterAudioDeviceCallback(callback)

    private fun route() {
        val usb = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val target = if (enabled) usb else null
        if (target?.id != current?.id) {
            current = target
            player.setPreferredAudioDevice(target)
            onChanged(target)
        }
    }
}
