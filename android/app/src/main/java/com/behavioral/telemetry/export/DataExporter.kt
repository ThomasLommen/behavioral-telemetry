package com.behavioral.telemetry.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.behavioral.telemetry.data.TelemetryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataExporter {

    suspend fun exportTelemetryJson(context: Context): File = withContext(Dispatchers.IO) {
        val db = TelemetryDatabase.getDatabase(context)
        val events = db.telemetryDao().getAllEvents()
        val digests = db.digestDao().getAllDigests()

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "telemetry_vault_$timeStamp.json")

        val rootJson = JSONObject().apply {
            put("export_version", 1)
            put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
            put("app_package", context.packageName)
            put("total_events", events.size)
            put("total_digests", digests.size)

            // Saved Gemini Digests
            val digestsArray = JSONArray()
            for (digest in digests) {
                digestsArray.put(JSONObject().apply {
                    put("id", digest.id)
                    put("timestamp_utc", digest.timestampUtc)
                    put("formatted_date", digest.formattedDate)
                    put("headline", digest.headline)
                    put("full_content", digest.fullContent)
                    put("is_automated", digest.isAutomated)
                })
            }
            put("saved_digests", digestsArray)

            // Raw Telemetry Events
            val eventsArray = JSONArray()
            for (event in events) {
                eventsArray.put(JSONObject().apply {
                    put("id", event.id)
                    put("timestamp_utc", event.timestampUtc)
                    put("iso_timestamp", event.isoTimestamp)
                    put("event_type", event.eventType)
                    put("package_name", event.packageName)
                    put("duration_ms", event.durationMs)
                    put("trigger_source", event.triggerSource)
                    if (event.metadataJson.isNotBlank()) {
                        try {
                            put("metadata", JSONObject(event.metadataJson))
                        } catch (e: Exception) {
                            put("metadata_raw", event.metadataJson)
                        }
                    }
                })
            }
            put("telemetry_events", eventsArray)
        }

        FileWriter(exportFile).use { writer ->
            writer.write(rootJson.toString(2))
        }

        exportFile
    }

    fun createShareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Behavioral Telemetry Vault Export (${file.name})")
            putExtra(Intent.EXTRA_TEXT, "Exported personal behavioral telemetry and Gemini analysis archives.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
