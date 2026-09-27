package com.behavioral.telemetry.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.Instant
import java.time.format.DateTimeFormatter

object UsageStatsHelper {

    fun hasUsagePermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun queryAndRecordForegroundApps(context: Context, startTimeMillis: Long, endTimeMillis: Long) {
        if (!hasUsagePermission(context)) return

        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val events = usageManager.queryEvents(startTimeMillis, endTimeMillis)
        val outEvent = UsageEvents.Event()

        val db = TelemetryDatabase.getDatabase(context)
        val entities = mutableListOf<TelemetryEntity>()

        while (events.hasNextEvent()) {
            events.getNextEvent(outEvent)
            // ACTIVITY_RESUMED (added in API 29) or MOVE_TO_FOREGROUND (API 21)
            val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                outEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }

            if (isForeground && outEvent.packageName != context.packageName) {
                val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(outEvent.timeStamp))
                entities.add(
                    TelemetryEntity(
                        timestampUtc = outEvent.timeStamp,
                        isoTimestamp = iso,
                        eventType = "APP_FOREGROUND",
                        packageName = outEvent.packageName,
                        triggerSource = "SYSTEM_USAGE_STATS"
                    )
                )
            }
        }

        for (entity in entities) {
            db.telemetryDao().insertEvent(entity)
        }
    }
}
