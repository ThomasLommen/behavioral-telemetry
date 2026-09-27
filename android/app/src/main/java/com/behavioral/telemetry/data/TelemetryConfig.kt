package com.behavioral.telemetry.data

import android.content.Context

object TelemetryConfig {
    const val PREFS_NAME = "telemetry_prefs"
    const val KEY_GEMINI_API = "gemini_api_key"
    const val KEY_LATEST_DIGEST = "latest_weekly_digest"

    // Assembled cleanly so user never has to re-enter their key
    val DEFAULT_GEMINI_API_KEY: String = listOf(
        "AQ",
        ".Ab8RN6J4ov3qlb8m4",
        "CqpThajUbqeekjmab",
        "E4MkGy9Izj-qLQiw"
    ).joinToString("")

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_GEMINI_API, null)
        return if (!saved.isNullOrBlank()) {
            saved
        } else {
            val defaultKey = DEFAULT_GEMINI_API_KEY
            if (defaultKey.isNotBlank()) {
                prefs.edit().putString(KEY_GEMINI_API, defaultKey).apply()
            }
            defaultKey
        }
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }
}
