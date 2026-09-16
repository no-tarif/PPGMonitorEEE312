package com.eee312.ppgmonitor.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface Spo2WindowDao {
    @Insert
    suspend fun insertAll(windows: List<Spo2WindowEntity>)

    @Query("SELECT * FROM spo2_windows WHERE readingId = :readingId ORDER BY tSec ASC")
    suspend fun getForReading(readingId: Long): List<Spo2WindowEntity>

    @Query("DELETE FROM spo2_windows WHERE readingId = :readingId")
    suspend fun deleteForReading(readingId: Long)
}
