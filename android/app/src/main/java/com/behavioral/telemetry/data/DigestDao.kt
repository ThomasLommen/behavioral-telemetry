package com.behavioral.telemetry.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DigestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDigest(digest: DigestEntity): Long

    @Query("SELECT * FROM saved_digests ORDER BY timestampUtc DESC")
    fun getAllDigestsFlow(): Flow<List<DigestEntity>>

    @Query("SELECT * FROM saved_digests ORDER BY timestampUtc DESC LIMIT 1")
    suspend fun getLatestDigest(): DigestEntity?

    @Query("DELETE FROM saved_digests WHERE id = :id")
    suspend fun deleteDigest(id: Long)

    @Query("DELETE FROM saved_digests")
    suspend fun clearAllDigests()
}
