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
import com.behavioral.telemetry.data.OnDeviceSynthesizer
import com.behavioral.telemetry.gemini.GeminiClient
import com.behavioral.telemetry.ui.MainActivity
import java.util.concurrent.TimeUnit

class WeeklyDigestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "weekly_digest_channel"
        const val NOTIFICATION_ID = 2002
        const val WORK_NAME = "weekly_behavioral_digest_work"

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
        val prefs = applicationContext.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            return Result.success()
        }

        return try {
            val summary = OnDeviceSynthesizer.buildTelemetrySummary(applicationContext)
            val result = GeminiClient.analyzeTelemetry(apiKey.trim(), summary)

            // Save result to preferences for MainActivity to show
            prefs.edit().putString("latest_weekly_digest", result).apply()

            // Post notification
            postDigestNotification(result)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun postDigestNotification(digestText: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Weekly Behavioral Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Delivers your automated weekly personal behavioral summary"
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Extract first 2 lines as headline preview
        val headline = digestText.lines()
            .firstOrNull { it.startsWith("**Headline:**") || it.contains("Headline") }
            ?: "Your new 30-second Behavioral Digest is ready."

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Weekly Behavioral Digest 📱")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(digestText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
