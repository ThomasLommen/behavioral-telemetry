package com.behavioral.telemetry.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.data.TelemetryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val eventType = when (action) {
            Intent.ACTION_POWER_CONNECTED -> "POWER_CONNECTED"
            Intent.ACTION_POWER_DISCONNECTED -> "POWER_DISCONNECTED"
            else -> return
        }

        val now = System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))

        val meta = JSONObject()
        try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100f / scale).toInt()
                    meta.put("battery_percent", pct)
                }
                val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val plugType = when (chargePlug) {
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                    else -> "OTHER"
                }
                meta.put("plug_type", plugType)
            }
        } catch (ignored: Exception) {}

        val entity = TelemetryEntity(
            timestampUtc = now,
            isoTimestamp = iso,
            eventType = eventType,
            metadataJson = meta.toString()
        )

        val db = TelemetryDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            db.telemetryDao().insertEvent(entity)
        }
    }
}
