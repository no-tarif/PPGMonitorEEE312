package com.eee312.ppgmonitor.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One PPG sample belonging to a [ReadingEntity], written in batches by
 * [SessionRecorder] (never one row per callback). Cascade-deletes with
 * its parent reading.
 */
@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = ReadingEntity::class,
            parentColumns = ["id"],
            childColumns = ["readingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("readingId")]
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingId: Long,
    /** Seconds from the start of the reading, derived from sample count /
     * sampleRateHz - not wall-clock time, so it stays correct regardless
     * of dataset playback speed. */
    val tSec: Double,
    val rawPpg: Double,
    val cleanedPpg: Double,
    val accMagnitude: Double,
    /** Non-null only on the sample where a new HR estimate was produced -
     * same convention as PpgPipeline.Output.newHrBpm. */
    val bpm: Double?
)
