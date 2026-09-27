package com.behavioral.telemetry.sensors

import android.app.NotificationManager
import android.content.Context
import android.os.Build

object FocusModeHelper {

    fun isDndActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val filter = nm.currentInterruptionFilter
            filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (e: Exception) {
            false
        }
    }

    fun getInterruptionFilterName(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "NORMAL"
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return "NORMAL"
            when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "NORMAL"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY_ONLY"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS_ONLY"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "TOTAL_SILENCE"
                else -> "NORMAL"
            }
        } catch (e: Exception) {
            "NORMAL"
        }
    }
}
