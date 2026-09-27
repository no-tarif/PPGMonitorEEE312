package com.eee312.ppgmonitor.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Narrow projection for the History PPG graph - three columns instead of
 * a whole [SampleEntity], read in id-keyed chunks so a 1 M-sample reading
 * never has to exist as a list of entities. */
data class WaveformPoint(val id: Long, val tSec: Double, val cleanedPpg: Double)

/** One stored HR estimate (non-null bpm) for the BPM-over-time graph. */
data class BpmPoint(val tSec: Double, val bpm: Double)

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE readingId = :readingId ORDER BY tSec ASC")
    suspend fun getForReading(readingId: Long): List<SampleEntity>

    /** Samples are inserted in time order, so id order == tSec order and
     * `id > afterId` gives cheap keyset paging (no OFFSET rescans). */
    @Query(
        "SELECT id, tSec, cleanedPpg FROM samples WHERE readingId = :readingId AND id > :afterId " +
            "ORDER BY id ASC LIMIT :limit"
    )
    suspend fun getWaveformChunk(readingId: Long, afterId: Long, limit: Int): List<WaveformPoint>

    /** Scales a reading's time base (used once, when a live reading's
     * measured rate differs from the nominal rate it was recorded with). */
    @Query("UPDATE samples SET tSec = tSec * :factor WHERE readingId = :readingId")
    suspend fun rescaleTime(readingId: Long, factor: Double)

    @Query("SELECT COUNT(*) FROM samples WHERE readingId = :readingId")
    suspend fun countForReading(readingId: Long): Int

    @Query("SELECT tSec, bpm FROM samples WHERE readingId = :readingId AND bpm IS NOT NULL ORDER BY id ASC")
    suspend fun getBpmSeries(readingId: Long): List<BpmPoint>
}
