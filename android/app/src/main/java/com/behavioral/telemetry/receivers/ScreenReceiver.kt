package com.behavioral.telemetry.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.data.TelemetryEntity
import com.behavioral.telemetry.data.UsageStatsHelper
import com.behavioral.telemetry.sensors.AmbientLightHelper
import com.behavioral.telemetry.sensors.AudioStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

class ScreenReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        var lastScreenOnTimestamp: Long = 0L

        @Volatile
        var lastUnlockTimestamp: Long = 0L

        @Volatile
        var lastAmbientLux: Float? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val now = System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))
        val db = TelemetryDatabase.getDatabase(context)

        when (action) {
            Intent.ACTION_SCREEN_ON -> {
                lastScreenOnTimestamp = now
                val audioCtx = AudioStateHelper.getAudioAndRingerContext(context)

                // Capture 1-shot ambient light sensor
                AmbientLightHelper.captureOneShotLux(context) { lux ->
                    lastAmbientLux = lux
                    val meta = JSONObject(audioCtx.toString())
                    if (lux != null) {
                        meta.put("ambient_lux", lux)
                        meta.put("is_dark_environment", lux < 5f)
                    }

                    val entity = TelemetryEntity(
                        timestampUtc = now,
                        isoTimestamp = iso,
                        eventType = "SCREEN_ON",
                        metadataJson = meta.toString()
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        db.telemetryDao().insertEvent(entity)
                    }
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                lastUnlockTimestamp = now
                val latencyFromOn = if (lastScreenOnTimestamp > 0L) now - lastScreenOnTimestamp else 0L
                val audioCtx = AudioStateHelper.getAudioAndRingerContext(context)
                if (lastAmbientLux != null) {
                    audioCtx.put("ambient_lux", lastAmbientLux)
                    audioCtx.put("is_dark_environment", (lastAmbientLux ?: 99f) < 5f)
                }
                audioCtx.put("wake_to_unlock_ms", latencyFromOn)

                val entity = TelemetryEntity(
                    timestampUtc = now,
                    isoTimestamp = iso,
                    eventType = "UNLOCK",
                    durationMs = latencyFromOn,
                    metadataJson = audioCtx.toString()
                )
                CoroutineScope(Dispatchers.IO).launch {
                    db.telemetryDao().insertEvent(entity)
                }
            }

            Intent.ACTION_SCREEN_OFF -> {
                val sessionStart = if (lastUnlockTimestamp > 0L) lastUnlockTimestamp else lastScreenOnTimestamp
                val sessionDuration = if (sessionStart > 0L) now - sessionStart else 0L
                val isMicroCheck = sessionDuration in 1..44999L

                val meta = JSONObject().apply {
                    put("session_duration_ms", sessionDuration)
                    put("is_micro_check", isMicroCheck)
                    if (lastAmbientLux != null) {
                        put("ambient_lux", lastAmbientLux)
                        put("is_dark_environment", (lastAmbientLux ?: 99f) < 5f)
                    }
                }

                val entity = TelemetryEntity(
                    timestampUtc = now,
                    isoTimestamp = iso,
                    eventType = "SCREEN_OFF",
                    durationMs = sessionDuration,
                    metadataJson = meta.toString()
                )

                CoroutineScope(Dispatchers.IO).launch {
                    db.telemetryDao().insertEvent(entity)

                    // Capture foreground apps during this session
                    val start = if (lastUnlockTimestamp > 0L) lastUnlockTimestamp else (now - 15 * 60 * 1000L)
                    UsageStatsHelper.queryAndRecordForegroundApps(context, start, now)

                    // Reset session trackers
                    lastUnlockTimestamp = 0L
                    lastScreenOnTimestamp = 0L
                    lastAmbientLux = null
                }
            }
        }
    }
}
