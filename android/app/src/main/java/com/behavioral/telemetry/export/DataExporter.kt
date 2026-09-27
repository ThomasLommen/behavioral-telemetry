package com.behavioral.telemetry.export

import android.content.Context
import android.os.Environment
import com.behavioral.telemetry.data.TelemetryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataExporter {

    suspend fun exportToJsonl(context: Context): File = withContext(Dispatchers.IO) {
        val db = TelemetryDatabase.getDatabase(context)
        val events = db.telemetryDao().getAllEvents()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "telemetry_export_$timeStamp.jsonl"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val exportFile = File(downloadsDir, fileName)

        FileWriter(exportFile).use { writer ->
            for (event in events) {
                val json = JSONObject().apply {
                    put("id", event.id)
                    put("timestamp", event.isoTimestamp)
                    put("event_type", event.eventType)
                    put("package_name", event.packageName)
                    put("duration_ms", event.durationMs)
                    put("trigger_source", event.triggerSource)
                    if (event.metadataJson.isNotBlank()) {
                        put("metadata", JSONObject(event.metadataJson))
                    }
                }
                writer.write(json.toString() + "\n")
            }
        }

        exportFile
    }
}
