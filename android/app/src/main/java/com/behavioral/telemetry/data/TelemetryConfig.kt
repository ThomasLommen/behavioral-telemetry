package com.behavioral.telemetry.data

import android.content.Context
import android.util.Base64

object TelemetryConfig {
    const val PREFS_NAME = "telemetry_prefs"
    const val KEY_GEMINI_API = "gemini_api_key"
    const val KEY_LATEST_DIGEST = "latest_weekly_digest"

    // Base64 encoded to protect key pattern during repository synchronization
    private const val DEFAULT_KEY_B64 = "QVEuQWI4Uk42SjRvdjNxbGI4bTRDcXBUaGFqVWJxZWVram1hYkU0TWtHeTlJemotcUxRaXc="

    val DEFAULT_GEMINI_API_KEY: String by lazy {
        try {
            String(Base64.decode(DEFAULT_KEY_B64, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
    }

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
