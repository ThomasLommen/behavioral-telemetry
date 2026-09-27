package com.behavioral.telemetry.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.behavioral.telemetry.data.DigestEntity
import com.behavioral.telemetry.data.OnDeviceSynthesizer
import com.behavioral.telemetry.data.TelemetryConfig
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.gemini.GeminiClient
import com.behavioral.telemetry.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class WeeklyDigestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "weekly_digest_channel"
        const val NOTIFICATION_ID = 2002
        const val WORK_NAME = "weekly_behavioral_digest_work"
        const val EXTRA_TARGET_TAB = "EXTRA_TARGET_TAB"
        const val EXTRA_DIGEST_ID = "EXTRA_DIGEST_ID"

        fun scheduleWeeklyDigest(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<WeeklyDigestWorker>(
                7, TimeUnit.DAYS
            ).setInitialDelay(24, TimeUnit.HOURS).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(TelemetryConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = TelemetryConfig.getApiKey(applicationContext)

        if (apiKey.isBlank()) {
            return Result.success()
        }

        return try {
            val summary = OnDeviceSynthesizer.buildTelemetrySummary(applicationContext)
            val result = GeminiClient.analyzeTelemetry(apiKey.trim(), summary)

            val now = System.currentTimeMillis()
            val sdf = SimpleDateFormat("MMM d, yyyy", Locale.US)
            val headline = result.lines()
                .firstOrNull { it.startsWith("**Headline:**") || it.contains("Headline") }
                ?.replace("**Headline:**", "")?.trim()
                ?: "Weekly Behavioral Briefing"

            val db = TelemetryDatabase.getDatabase(applicationContext)
            val insertedId = db.digestDao().insertDigest(
                DigestEntity(
                    timestampUtc = now,
                    formattedDate = sdf.format(Date(now)),
                    headline = headline,
                    fullContent = result,
                    isAutomated = true
                )
            )

            // Auto-Pruning Policy: Retain all digests permanently, but purge raw event logs > 30 days
            val thirtyDaysAgo = now - TimeUnit.DAYS.toMillis(30)
            db.telemetryDao().deleteOldEvents(thirtyDaysAgo)

            // Save result to preferences for MainActivity cached preview
            prefs.edit().putString(TelemetryConfig.KEY_LATEST_DIGEST, result).apply()

            // Post rich notification deep-linking directly into the Archive tab
            postDigestNotification(insertedId, headline, result)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun postDigestNotification(digestId: Long, headline: String, digestText: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Weekly Behavioral Digest",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Delivers your automated weekly personal behavioral briefing and habits analysis."
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_TAB, 2) // Tab 2 = Archive Tab
            putExtra(EXTRA_DIGEST_ID, digestId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Trim digest for notification body preview
        val cleanPreview = digestText.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(4)
            .joinToString("\n")

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Weekly Behavioral Briefing 🧠")
            .setContentText(headline)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(headline)
                    .bigText(cleanPreview)
                    .setSummaryText("Tap to read full analysis")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
