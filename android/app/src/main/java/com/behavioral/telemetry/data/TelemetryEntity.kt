package com.behavioral.telemetry.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry_events")
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampUtc: Long,          // Epoch millis
    val isoTimestamp: String,        // ISO-8601 string
    val eventType: String,           // SCREEN_ON, SCREEN_OFF, UNLOCK, POWER_CONNECTED, etc.
    val packageName: String? = null,
    val durationMs: Long = 0,
    val triggerSource: String? = null,
    val metadataJson: String = "{}"
)
