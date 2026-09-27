package com.behavioral.telemetry.sensors

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.json.JSONObject

object AudioStateHelper {

    fun getAudioAndRingerContext(context: Context): JSONObject {
        val json = JSONObject()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return json

            val ringer = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "SILENT"
                AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
                else -> "UNKNOWN"
            }
            json.put("ringer_mode", ringer)
            json.put("is_music_active", audioManager.isMusicActive)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasHeadphones = devices.any { device ->
                    device.type in listOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_USB_HEADSET
                    )
                }
                json.put("has_headphones", hasHeadphones)
            }
        } catch (e: Exception) {
            // Non-critical telemetry context fails gracefully
        }
        return json
    }
}
