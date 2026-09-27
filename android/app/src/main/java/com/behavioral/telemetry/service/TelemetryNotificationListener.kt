package com.behavioral.telemetry.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.data.TelemetryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

class TelemetryNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return // Ignore self-notifications

        val now = System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))

        val entity = TelemetryEntity(
            timestampUtc = now,
            isoTimestamp = iso,
            eventType = "NOTIFICATION_POSTED",
            packageName = pkg,
            triggerSource = "SYSTEM_NOTIFICATION"
        )

        val db = TelemetryDatabase.getDatabase(this)
        CoroutineScope(Dispatchers.IO).launch {
            db.telemetryDao().insertEvent(entity)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        val now = System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))

        val entity = TelemetryEntity(
            timestampUtc = now,
            isoTimestamp = iso,
            eventType = "NOTIFICATION_DISMISSED",
            packageName = pkg
        )

        val db = TelemetryDatabase.getDatabase(this)
        CoroutineScope(Dispatchers.IO).launch {
            db.telemetryDao().insertEvent(entity)
        }
    }
}
