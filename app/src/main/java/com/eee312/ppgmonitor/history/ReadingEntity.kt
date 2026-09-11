package com.eee312.ppgmonitor.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded session (Simulated / Dataset / ESP32). Created lazily by
 * [SessionRecorder] once a session has run long enough to be worth
 * keeping (see MIN_DURATION_SEC there) - summary stats (durationSec,
 * avgBpm, minBpm, maxBpm, meanSqi) start null/0 and are filled in by
 * [SessionRecorder.finish] once the session ends.
 */
@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "Reading N" - N from [ReadingNumberCounter], never reused. Renamable by the user. */
    val name: String,
    val sourceType: SourceType,
    /** Non-null only when sourceType == DATASET - the dataset id (matches datasets.json). */
    val datasetId: String?,
    val startedAtEpochMs: Long,
    val durationSec: Double,
    val sampleRateHz: Double,
    val avgBpm: Double?,
    val minBpm: Double?,
    val maxBpm: Double?,
    val meanSqi: Double?,
    val notes: String?
)
