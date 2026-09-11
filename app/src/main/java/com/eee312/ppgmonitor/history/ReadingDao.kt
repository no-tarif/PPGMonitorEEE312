package com.eee312.ppgmonitor.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(reading: ReadingEntity): Long

    @Query("SELECT * FROM readings ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getById(id: Long): ReadingEntity?

    @Query(
        "UPDATE readings SET durationSec = :durationSec, avgBpm = :avgBpm, " +
            "minBpm = :minBpm, maxBpm = :maxBpm, meanSqi = :meanSqi WHERE id = :id"
    )
    suspend fun updateSummary(
        id: Long,
        durationSec: Double,
        avgBpm: Double?,
        minBpm: Double?,
        maxBpm: Double?,
        meanSqi: Double?
    )

    @Query("UPDATE readings SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Delete
    suspend fun delete(reading: ReadingEntity)
}
