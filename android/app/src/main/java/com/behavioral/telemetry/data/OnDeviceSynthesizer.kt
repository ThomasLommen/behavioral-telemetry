package com.behavioral.telemetry.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object OnDeviceSynthesizer {

    suspend fun buildTelemetrySummary(context: Context): String = withContext(Dispatchers.IO) {
        val db = TelemetryDatabase.getDatabase(context)
        val events = db.telemetryDao().getAllEvents()
        val priorDigests = db.digestDao().getAllDigests()

        if (events.isEmpty()) {
            return@withContext "No telemetry events logged yet. Continue using your phone normally to gather baseline data."
        }

        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfTime = SimpleDateFormat("HH:mm", Locale.US)

        val sb = StringBuilder()

        // -------------------------------------------------------------
        // SECTION 1: Executive Daily Volume Table
        // -------------------------------------------------------------
        val byDate = events.groupBy { sdfDate.format(Date(it.timestampUtc)) }
        sb.append("### 📊 Daily Behavioral Volume:\n")
        sb.append("| Date | Total Unlocks | Micro-Checks (<45s) | Dark-Room (Lux<5) | Focus Breaches (DND) | Net (Wi-Fi / Cell) |\n")
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n")

        for ((date, dayEvents) in byDate) {
            val unlocks = dayEvents.count { it.eventType == "UNLOCK" }
            val microChecks = dayEvents.count { e ->
                if (e.eventType == "SCREEN_OFF") {
                    e.durationMs in 1..44999L || parseMeta(e.metadataJson).optBoolean("is_micro_check", false)
                } else false
            }
            val darkSessions = dayEvents.count { e ->
                parseMeta(e.metadataJson).optBoolean("is_dark_environment", false)
            }
            val focusBreaches = dayEvents.count { e ->
                e.eventType == "UNLOCK" && parseMeta(e.metadataJson).optBoolean("is_focus_breach", false)
            }
            val wifiUnlocks = dayEvents.count { e -> e.eventType == "UNLOCK" && parseMeta(e.metadataJson).optString("network_type") == "WIFI" }
            val cellUnlocks = dayEvents.count { e -> e.eventType == "UNLOCK" && parseMeta(e.metadataJson).optString("network_type") == "CELLULAR" }
            sb.append("| $date | $unlocks | $microChecks | $darkSessions | $focusBreaches | ${wifiUnlocks}W / ${cellUnlocks}C |\n")
        }

        // -------------------------------------------------------------
        // SECTION 2: Circadian & Bedtime Boundaries
        // -------------------------------------------------------------
        sb.append("\n### 🌙 Circadian & Bedtime Anchor Analysis:\n")
        var totalDarkMins = 0L
        var totalBedtimeProcrastinationMins = 0L
        var bedtimeEventsCount = 0

        for ((date, dayEvents) in byDate) {
            val nightCharger = dayEvents.filter { e ->
                e.eventType == "POWER_CONNECTED" && isNightHour(e.timestampUtc)
            }.maxByOrNull { it.timestampUtc }

            val lastNightLock = dayEvents.filter { e ->
                e.eventType == "SCREEN_OFF" && isNightHour(e.timestampUtc)
            }.maxByOrNull { it.timestampUtc }

            if (nightCharger != null && lastNightLock != null && lastNightLock.timestampUtc > nightCharger.timestampUtc) {
                val delayMs = lastNightLock.timestampUtc - nightCharger.timestampUtc
                val delayMins = TimeUnit.MILLISECONDS.toMinutes(delayMs)
                if (delayMins in 1..180) {
                    totalBedtimeProcrastinationMins += delayMins
                    bedtimeEventsCount++
                    sb.append("- **$date Bedtime Procrastination:** Plugged in at ${sdfTime.format(Date(nightCharger.timestampUtc))}, but stayed active on phone until ${sdfTime.format(Date(lastNightLock.timestampUtc))} ($delayMins mins in-bed phone delay).\n")
                }
            }

            // Dark room doomscrolling minutes
            val darkScreenOffs = dayEvents.filter { e ->
                e.eventType == "SCREEN_OFF" && parseMeta(e.metadataJson).optBoolean("is_dark_environment", false)
            }
            val darkMins = TimeUnit.MILLISECONDS.toMinutes(darkScreenOffs.sumOf { it.durationMs })
            if (darkMins > 0) {
                totalDarkMins += darkMins
                sb.append("- **$date Dark-Room Exposure:** $darkMins minutes of screen activity in pitch-black ambient lighting (< 5 lux).\n")
            }
        }

        if (bedtimeEventsCount > 0) {
            val avgDelay = totalBedtimeProcrastinationMins / bedtimeEventsCount
            sb.append("- **Average Bedtime Delay After Nightstand Plug-in:** $avgDelay minutes.\n")
        }

        // -------------------------------------------------------------
        // SECTION 3: Attentional Fragmentation & Micro-Checking
        // -------------------------------------------------------------
        val allUnlocks = events.count { it.eventType == "UNLOCK" }
        val allScreenOffs = events.filter { it.eventType == "SCREEN_OFF" }
        val allMicroChecks = allScreenOffs.count { it.durationMs in 1..44999L || parseMeta(it.metadataJson).optBoolean("is_micro_check", false) }
        val microRatio = if (allUnlocks > 0) (allMicroChecks * 100 / allUnlocks) else 0

        sb.append("\n### ⚡ Attentional Restlessness & Micro-Checking:\n")
        sb.append("- **Total Unlock Sessions:** $allUnlocks\n")
        sb.append("- **Sub-45s Compulsive Micro-Checks:** $allMicroChecks ($microRatio% of total unlocks)\n")

        val appEvents = events.filter { it.eventType == "APP_FOREGROUND" }
        if (allUnlocks > 0 && appEvents.isNotEmpty()) {
            val switchesPerUnlock = String.format(Locale.US, "%.1f", appEvents.size.toFloat() / allUnlocks.coerceAtLeast(1))
            sb.append("- **App-Hopping Velocity:** Average of $switchesPerUnlock foreground app switches per unlock session.\n")
        }

        val headphoneEvents = events.count { parseMeta(it.metadataJson).optBoolean("has_headphones", false) }
        val musicEvents = events.count { parseMeta(it.metadataJson).optBoolean("is_music_active", false) }
        val silentEvents = events.count { parseMeta(it.metadataJson).optString("ringer_mode") == "SILENT" }
        sb.append("- **Focus Audio State:** $headphoneEvents events with headphones connected, $musicEvents with background audio playing, $silentEvents with ringer set to Silent.\n")

        // -------------------------------------------------------------
        // SECTION 4: Environmental & Focus Anchors (Wi-Fi, Commute, DND)
        // -------------------------------------------------------------
        val wifiUnlocksTotal = events.count { it.eventType == "UNLOCK" && parseMeta(it.metadataJson).optString("network_type") == "WIFI" }
        val cellUnlocksTotal = events.count { it.eventType == "UNLOCK" && parseMeta(it.metadataJson).optString("network_type") == "CELLULAR" }
        val dndBreachesTotal = events.count { it.eventType == "UNLOCK" && parseMeta(it.metadataJson).optBoolean("is_focus_breach", false) }
        val carAudioEventsTotal = events.count { parseMeta(it.metadataJson).optBoolean("has_car_audio", false) }

        sb.append("\n### 🌐 Environmental Context & Focus Boundaries:\n")
        sb.append("- **Network Context Distribution:** $wifiUnlocksTotal unlocks on Wi-Fi (Home/Office) vs $cellUnlocksTotal on Cellular (Transit/Commute).\n")
        if (dndBreachesTotal > 0) {
            sb.append("- **⚠️ Focus Rule Violations (DND Breaches):** $dndBreachesTotal unlocks occurred while Do Not Disturb / Focus Mode was actively enabled.\n")
        } else {
            sb.append("- **Focus Rule Adherence:** 0 DND breaches detected. Focus boundaries maintained.\n")
        }
        if (carAudioEventsTotal > 0) {
            sb.append("- **🚗 In-Vehicle Interactions:** $carAudioEventsTotal events recorded while connected to car audio / vehicle Bluetooth.\n")
        }

        // -------------------------------------------------------------
        // SECTION 5: Top Foreground Application Triggers
        // -------------------------------------------------------------
        if (appEvents.isNotEmpty()) {
            val topApps = appEvents.groupBy { it.packageName ?: "Unknown" }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(6)

            sb.append("\n### 📱 Dominant Foreground Applications (Context Switch Count):\n")
            for ((pkg, count) in topApps) {
                val cleanName = cleanPackageName(pkg)
                sb.append("1. **$cleanName** (`$pkg`): $count foreground launches\n")
            }
        }

        // -------------------------------------------------------------
        // SECTION 6: Notification Trap Detection (B = MAP)
        // -------------------------------------------------------------
        val notifEvents = events.filter { it.eventType == "NOTIFICATION_POSTED" }
        if (notifEvents.isNotEmpty()) {
            sb.append("\n### 🔔 External Notification Pressure (Fogg Behavior Model):\n")
            sb.append("- Total Incoming Notifications Logged: ${notifEvents.size}\n")
            val topNotifSources = notifEvents.groupBy { it.packageName ?: "Unknown" }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(4)
            sb.append("- Top Notification Senders: ")
            sb.append(topNotifSources.joinToString(", ") { "${cleanPackageName(it.first)} (${it.second})" })
            sb.append("\n")
        }

        // -------------------------------------------------------------
        // SECTION 7: Longitudinal Prior Digest Baselines (Habit Drift)
        // -------------------------------------------------------------
        if (priorDigests.isNotEmpty()) {
            sb.append("\n### 🗓️ Historical Baselines (Previous Digests):\n")
            val recentDigests = priorDigests.take(3)
            for (d in recentDigests) {
                sb.append("- **[${d.formattedDate}]** Headline: \"${d.headline}\"\n")
            }
        }

        sb.toString()
    }

    private fun parseMeta(jsonStr: String): JSONObject {
        return try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun isNightHour(epochMillis: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= 21 || hour < 5
    }

    private fun cleanPackageName(pkg: String): String {
        return pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
    }
}
