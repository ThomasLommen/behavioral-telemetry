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
                var hasHeadphones = false
                var hasCarAudio = false

                for (device in devices) {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> {
                            hasHeadphones = true
                        }
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                            hasHeadphones = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val name = device.productName.toString().lowercase()
                                if (name.contains("car") || name.contains("auto") || name.contains("sync") ||
                                    name.contains("handsfree") || name.contains("uconnect") || name.contains("bmw") ||
                                    name.contains("audi") || name.contains("toyota") || name.contains("tesla")) {
                                    hasCarAudio = true
                                }
                            }
                        }
                    }
                }
                json.put("has_headphones", hasHeadphones)
                json.put("has_car_audio", hasCarAudio)
            }
        } catch (e: Exception) {
            // Non-critical telemetry context fails gracefully
        }
        return json
    }
}
