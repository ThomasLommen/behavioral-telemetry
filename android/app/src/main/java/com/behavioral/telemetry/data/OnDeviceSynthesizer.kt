package com.behavioral.telemetry.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OnDeviceSynthesizer {

    suspend fun buildTelemetrySummary(context: Context): String = withContext(Dispatchers.IO) {
        val db = TelemetryDatabase.getDatabase(context)
        val events = db.telemetryDao().getAllEvents()

        if (events.isEmpty()) {
            return@withContext "No telemetry events logged yet."
        }

        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.US)

        val byDate = events.groupBy { sdfDate.format(Date(it.timestampUtc)) }

        val sb = StringBuilder()
        sb.append("### Logged Telemetry Summary:\n")
        sb.append("| Date | Total Events | Unlocks | Screen-Offs | Charging Cycles |\n")
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n")

        for ((date, dayEvents) in byDate) {
            val totalEvents = dayEvents.size
            val unlocks = dayEvents.count { it.eventType == "UNLOCK" }
            val screenOffs = dayEvents.count { it.eventType == "SCREEN_OFF" }
            val powerEvents = dayEvents.count { it.eventType.startsWith("POWER_") }
            sb.append("| $date | $totalEvents | $unlocks | $screenOffs | $powerEvents |\n")
        }

        sb.append("\n### Recent Interaction Timeline (Sample of Last 50 Events):\n")
        val recent = events.takeLast(50)
        for (e in recent) {
            val time = sdfTime.format(Date(e.timestampUtc))
            val date = sdfDate.format(Date(e.timestampUtc))
            val pkg = if (e.packageName != null) " (app: ${e.packageName})" else ""
            sb.append("- [$date $time] `${e.eventType}`$pkg\n")
        }

        sb.toString()
    }
}
