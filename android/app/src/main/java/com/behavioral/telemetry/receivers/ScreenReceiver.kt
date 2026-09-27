package com.behavioral.telemetry.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.data.TelemetryEntity
import com.behavioral.telemetry.data.UsageStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

class ScreenReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        var lastUnlockTimestamp: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val eventType = when (action) {
            Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
            Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
            Intent.ACTION_USER_PRESENT -> {
                lastUnlockTimestamp = System.currentTimeMillis()
                "UNLOCK"
            }
            else -> return
        }

        val now = System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))

        val entity = TelemetryEntity(
            timestampUtc = now,
            isoTimestamp = iso,
            eventType = eventType
        )

        val db = TelemetryDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            db.telemetryDao().insertEvent(entity)

            // When screen turns off, capture the foreground apps used during this session
            if (eventType == "SCREEN_OFF") {
                val start = if (lastUnlockTimestamp > 0L) lastUnlockTimestamp else (now - 15 * 60 * 1000L)
                UsageStatsHelper.queryAndRecordForegroundApps(context, start, now)
                lastUnlockTimestamp = 0L
            }
        }
    }
}
