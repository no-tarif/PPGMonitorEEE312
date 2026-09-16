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
    val bpm: Double?,
    /** Raw ir/red/green ADC counts for this sample - added for SpO2
     * (schema v2). Null for every sample recorded before this column
     * existed (LEGACY readings), and null for red/green on sources that
     * never had that channel (e.g. red on "Hardware - Running 2"). irRaw
     * duplicates the same value as [rawPpg] (both come from sample.ir)
     * but as the untouched Int the sensor reported, since SpO2's DC/PI
     * math needs the raw channel independent of whatever PpgPipeline.
     * Output.rawPpg happens to alias today. */
    val irRaw: Int? = null,
    val redRaw: Int? = null,
    val greenRaw: Int? = null,
    /** HeartRateEstimator.lastSqi, stored ONLY on the same sample as a
     * non-null [bpm] (same gating, so the two columns' non-null values
     * line up 1:1 as a per-window series in chronological order). Added
     * alongside the SpO2 columns because without it, dsp/quality's
     * "reuse the app's existing per-window SQI" check (Phase 2) would
     * have nothing to read for every future recording - see the Phase 0
     * report, which flagged that only an aggregate meanSqi existed
     * before this migration. Null for every pre-existing (LEGACY) sample. */
    val sqi: Double? = null
)
