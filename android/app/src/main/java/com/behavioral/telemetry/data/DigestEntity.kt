package com.behavioral.telemetry.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_digests")
data class DigestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestampUtc: Long,          // Epoch millis when generated
    val formattedDate: String,       // e.g. "Sep 27, 2026"
    val headline: String,            // One-sentence summary preview
    val fullContent: String,         // Complete Executive Digest markdown
    val isAutomated: Boolean = false // Triggered by WorkManager vs manual button
)
