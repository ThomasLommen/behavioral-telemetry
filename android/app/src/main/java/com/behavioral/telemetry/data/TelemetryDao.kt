package com.behavioral.telemetry.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: TelemetryEntity): Long

    @Query("SELECT * FROM telemetry_events ORDER BY timestampUtc ASC")
    suspend fun getAllEvents(): List<TelemetryEntity>

    @Query("SELECT * FROM telemetry_events WHERE timestampUtc >= :sinceEpochMillis ORDER BY timestampUtc ASC")
    suspend fun getEventsSince(sinceEpochMillis: Long): List<TelemetryEntity>

    @Query("SELECT COUNT(*) FROM telemetry_events")
    fun getEventCountFlow(): Flow<Int>

    @Query("DELETE FROM telemetry_events WHERE timestampUtc < :cutoffMillis")
    suspend fun deleteOldEvents(cutoffMillis: Long): Int

    @Query("DELETE FROM telemetry_events")
    suspend fun clearAll()
}
