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

    @Query("UPDATE readings SET sampleRateHz = :sampleRateHz, measuredRateHz = :measuredRateHz WHERE id = :id")
    suspend fun updateRates(id: Long, sampleRateHz: Double, measuredRateHz: Double?)

    @Query("UPDATE readings SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE readings SET spo2Status = :status WHERE id = :id")
    suspend fun updateSpo2Status(id: Long, status: Spo2Status)

    @Query(
        "UPDATE readings SET spo2Status = :status, spo2Median = :spo2Median, " +
            "spo2Min = :spo2Min, spo2Max = :spo2Max, spo2AcceptedPct = :spo2AcceptedPct, " +
            "rMedian = :rMedian, piIrMedian = :piIrMedian, piRedMedian = :piRedMedian, " +
            "spo2Curve = :spo2Curve, spo2CalibrationInfo = :spo2CalibrationInfo WHERE id = :id"
    )
    suspend fun updateSpo2Summary(
        id: Long,
        status: Spo2Status,
        spo2Median: Double?,
        spo2Min: Double?,
        spo2Max: Double?,
        spo2AcceptedPct: Double?,
        rMedian: Double?,
        piIrMedian: Double?,
        piRedMedian: Double?,
        spo2Curve: String?,
        spo2CalibrationInfo: String?
    )

    @Query("UPDATE readings SET qualityVerdict = :verdict, qualityJson = :json WHERE id = :id")
    suspend fun updateQuality(id: Long, verdict: String, json: String)

    @Delete
    suspend fun delete(reading: ReadingEntity)
}
