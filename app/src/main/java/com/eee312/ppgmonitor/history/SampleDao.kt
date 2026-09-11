package com.eee312.ppgmonitor.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE readingId = :readingId ORDER BY tSec ASC")
    suspend fun getForReading(readingId: Long): List<SampleEntity>
}
