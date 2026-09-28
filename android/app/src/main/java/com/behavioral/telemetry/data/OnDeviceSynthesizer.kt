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
        val sdfTimeSec = SimpleDateFormat("HH:mm:ss", Locale.US)

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
                if (delayMins in 1..240) {
                    totalBedtimeProcrastinationMins += delayMins
                    bedtimeEventsCount++
                    sb.append("- **$date Bedtime Procrastination:** Plugged in at ${sdfTime.format(Date(nightCharger.timestampUtc))}, but stayed active on phone until ${sdfTime.format(Date(lastNightLock.timestampUtc))} ($delayMins mins in-bed phone delay).\n")
                }
            }

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
        // SECTION 3: Attentional Restlessness & Ghost Checks
        // -------------------------------------------------------------
        val allUnlocks = events.count { it.eventType == "UNLOCK" }
        val allScreenOffs = events.filter { it.eventType == "SCREEN_OFF" }
        val allMicroChecks = allScreenOffs.count { it.durationMs in 1..44999L || parseMeta(it.metadataJson).optBoolean("is_micro_check", false) }
        val microRatio = if (allUnlocks > 0) (allMicroChecks * 100 / allUnlocks) else 0

        // Subconscious Ghost Checks: Unlocks where user opened 0 apps before locking again
        var ghostCheckCount = 0
        var totalGhostDurationMs = 0L
        val unlockEvents = events.filter { it.eventType == "UNLOCK" }
        for (u in unlockEvents) {
            val off = events.firstOrNull { it.eventType == "SCREEN_OFF" && it.timestampUtc >= u.timestampUtc }
            if (off != null) {
                val hasApps = events.any {
                    it.eventType == "APP_FOREGROUND" && it.timestampUtc in u.timestampUtc..off.timestampUtc
                }
                val duration = off.timestampUtc - u.timestampUtc
                if (!hasApps && duration in 1..15000L) {
                    ghostCheckCount++
                    totalGhostDurationMs += duration
                }
            }
        }
        val ghostRatio = if (allUnlocks > 0) (ghostCheckCount * 100 / allUnlocks) else 0
        val avgGhostSec = if (ghostCheckCount > 0) String.format(Locale.US, "%.1f", (totalGhostDurationMs / ghostCheckCount) / 1000f) else "0"

        sb.append("\n### ⚡ Attentional Restlessness & Ghost Reflexes:\n")
        sb.append("- **Total Unlock Sessions:** $allUnlocks\n")
        sb.append("- **Sub-45s Compulsive Micro-Checks:** $allMicroChecks ($microRatio% of total unlocks)\n")
        sb.append("- **👻 Ghost Checks (Zero-Action Reflexes):** $ghostCheckCount unlocks ($ghostRatio% of total) where you unlocked the phone for an average of ${avgGhostSec}s with ZERO apps launched (pure unconscious twitch).\n")

        val appEvents = events.filter { it.eventType == "APP_FOREGROUND" }
        if (allUnlocks > 0 && appEvents.isNotEmpty()) {
            val switchesPerUnlock = String.format(Locale.US, "%.1f", appEvents.size.toFloat() / allUnlocks.coerceAtLeast(1))
            sb.append("- **App-Hopping Velocity:** Average of $switchesPerUnlock foreground app switches per unlock session.\n")
        }

        // -------------------------------------------------------------
        // SECTION 4: Subconscious Gateway App Transition Chains
        // -------------------------------------------------------------
        val transitionCounts = mutableMapOf<Pair<String, String>, Int>()
        for (i in 0 until appEvents.size - 1) {
            val a1 = appEvents[i]
            val a2 = appEvents[i + 1]
            val p1 = a1.packageName
            val p2 = a2.packageName
            if (!p1.isNullOrBlank() && !p2.isNullOrBlank() && p1 != p2) {
                val diff = a2.timestampUtc - a1.timestampUtc
                if (diff in 1..15000L) {
                    val hasLockBetween = events.any {
                        it.eventType == "SCREEN_OFF" && it.timestampUtc in a1.timestampUtc..a2.timestampUtc
                    }
                    if (!hasLockBetween) {
                        val pair = Pair(cleanPackageName(p1), cleanPackageName(p2))
                        transitionCounts[pair] = (transitionCounts[pair] ?: 0) + 1
                    }
                }
            }
        }

        if (transitionCounts.isNotEmpty()) {
            val topChains = transitionCounts.toList().sortedByDescending { it.second }.take(4)
            sb.append("\n### 🕳️ Subconscious Gateway Chains (App Hops <15s without locking):\n")
            for ((chain, count) in topChains) {
                sb.append("- **${chain.first} ➔ ${chain.second}:** $count rapid hops (App ${chain.first} repeatedly triggers an involuntary jump to ${chain.second}).\n")
            }
        }

        // -------------------------------------------------------------
        // SECTION 5: Notification Hijack Patterns (Trigger ➔ Rabbit Hole)
        // -------------------------------------------------------------
        val notifEvents = events.filter { it.eventType == "NOTIFICATION_POSTED" }
        val hijackCounts = mutableMapOf<Pair<String, String>, Int>()
        for (n in notifEvents) {
            val nPkg = n.packageName ?: continue
            val u = events.firstOrNull { it.eventType == "UNLOCK" && it.timestampUtc in n.timestampUtc..(n.timestampUtc + 90000L) }
            if (u != null) {
                val firstApp = events.firstOrNull { it.eventType == "APP_FOREGROUND" && it.timestampUtc in u.timestampUtc..(u.timestampUtc + 15000L) }
                if (firstApp != null) {
                    val appPkg = firstApp.packageName ?: continue
                    if (cleanPackageName(appPkg) != cleanPackageName(nPkg)) {
                        val pair = Pair(cleanPackageName(nPkg), cleanPackageName(appPkg))
                        hijackCounts[pair] = (hijackCounts[pair] ?: 0) + 1
                    }
                }
            }
        }

        if (hijackCounts.isNotEmpty()) {
            val topHijacks = hijackCounts.toList().sortedByDescending { it.second }.take(3)
            sb.append("\n### 🎣 Notification Hijack Detours (Alert Received ➔ Different App Opened):\n")
            for ((detour, count) in topHijacks) {
                sb.append("- **Alert from ${detour.first} ➔ Detoured into ${detour.second}:** $count times (You unlocked to check ${detour.first}, but were immediately pulled into ${detour.second}).\n")
            }
        }

        // -------------------------------------------------------------
        // SECTION 6: Hourly Attentional Slump Curve
        // -------------------------------------------------------------
        val hourMicroCounts = IntArray(24)
        for (e in events) {
            if (e.eventType == "SCREEN_OFF" && (e.durationMs in 1..44999L || parseMeta(e.metadataJson).optBoolean("is_micro_check", false))) {
                val cal = Calendar.getInstance().apply { timeInMillis = e.timestampUtc }
                hourMicroCounts[cal.get(Calendar.HOUR_OF_DAY)]++
            }
        }
        val peakHour = hourMicroCounts.indices.maxByOrNull { hourMicroCounts[it] } ?: 14
        val peakCount = hourMicroCounts[peakHour]
        if (peakCount > 0) {
            sb.append("\n### 📉 Daily Fatigue & Slump Windows:\n")
            sb.append("- **Peak Attentional Slump:** ${String.format(Locale.US, "%02d:00 - %02d:00", peakHour, (peakHour + 1) % 24)} accounted for $peakCount rapid micro-checks (highest concentration of fragmented focus across the day).\n")
        }

        // -------------------------------------------------------------
        // SECTION 7: Environmental Context & Focus Boundaries
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
        // SECTION 8: Dominant Foreground Applications
        // -------------------------------------------------------------
        if (appEvents.isNotEmpty()) {
            val topApps = appEvents.groupBy { cleanPackageName(it.packageName ?: "Unknown") }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(6)

            sb.append("\n### 📱 Dominant Foreground Applications (Context Switch Count):\n")
            for ((name, count) in topApps) {
                sb.append("1. **$name**: $count foreground launches\n")
            }
        }

        // -------------------------------------------------------------
        // SECTION 9: Forensic Micro-Timeline (High-Entropy Chronological Episode)
        // -------------------------------------------------------------
        // Extract 15-20 sequential events from a peak session to give Gemini raw chronological grounding
        val significantEvents = events.filter { it.eventType in listOf("UNLOCK", "APP_FOREGROUND", "NOTIFICATION_POSTED", "SCREEN_OFF", "POWER_CONNECTED") }
        if (significantEvents.size >= 10) {
            // Find a slice from late afternoon or evening
            val startIndex = (significantEvents.size - 25).coerceAtLeast(0)
            val sampleSlice = significantEvents.subList(startIndex, (startIndex + 20).coerceAtMost(significantEvents.size))

            sb.append("\n### 🔬 Forensic Micro-Timeline (Chronological Evidence Slice):\n")
            for (se in sampleSlice) {
                val timeStr = sdfTimeSec.format(Date(se.timestampUtc))
                val meta = parseMeta(se.metadataJson)
                when (se.eventType) {
                    "UNLOCK" -> {
                        val net = meta.optString("network_type", "WIFI")
                        val dnd = if (meta.optBoolean("is_focus_breach", false)) " [DND_BREACH]" else ""
                        val lux = if (meta.has("ambient_lux")) "${meta.optDouble("ambient_lux", 0.0).toInt()}lx" else ""
                        val latency = if (se.durationMs > 0) "${se.durationMs}ms hesitation" else ""
                        sb.append("- $timeStr [UNLOCK]$dnd ($net, $lux, $latency)\n")
                    }
                    "APP_FOREGROUND" -> {
                        sb.append("- $timeStr [APP] ${cleanPackageName(se.packageName ?: "")}\n")
                    }
                    "NOTIFICATION_POSTED" -> {
                        sb.append("- $timeStr [ALERT] from ${cleanPackageName(se.packageName ?: "")}\n")
                    }
                    "SCREEN_OFF" -> {
                        val durSec = se.durationMs / 1000
                        val isMicro = if (se.durationMs in 1..44999L) " (Micro-Check)" else ""
                        sb.append("- $timeStr [SCREEN_OFF] session ${durSec}s$isMicro\n")
                    }
                    "POWER_CONNECTED" -> {
                        sb.append("- $timeStr [CHARGER_PLUGGED_IN]\n")
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // SECTION 10: Longitudinal Prior Digest Baselines (Habit Drift)
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
        val lower = pkg.lowercase()
        return when {
            lower.contains("instagram") -> "Instagram"
            lower.contains("reddit") -> "Reddit"
            lower.contains("slack") -> "Slack"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("twitter") || lower.contains(".x.") || lower.endsWith(".x") -> "X (Twitter)"
            lower.contains("youtube") -> "YouTube"
            lower.contains("tiktok") -> "TikTok"
            lower.contains("chrome") -> "Chrome"
            lower.contains("gmail") -> "Gmail"
            lower.contains("discord") -> "Discord"
            lower.contains("spotify") -> "Spotify"
            lower.contains("telegram") -> "Telegram"
            lower.contains("netflix") -> "Netflix"
            lower.contains("linkedin") -> "LinkedIn"
            lower.contains("duolingo") -> "Duolingo"
            else -> {
                val parts = pkg.split(".").filter { it !in listOf("com", "android", "app", "mobile", "client") }
                parts.lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
            }
        }
    }
}
