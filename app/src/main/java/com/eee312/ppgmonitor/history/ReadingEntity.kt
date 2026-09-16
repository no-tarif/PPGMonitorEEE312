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
    val notes: String?,
    // --- SpO2 + quality summary (schema v2) - all null until the batch
    // pass runs (or forever, for LEGACY readings that are never
    // recomputed automatically - see ReadingPostProcessor). ---
    val spo2Status: Spo2Status? = null,
    val spo2Median: Double? = null,
    val spo2Min: Double? = null,
    val spo2Max: Double? = null,
    val spo2AcceptedPct: Double? = null,
    val rMedian: Double? = null,
    val piIrMedian: Double? = null,
    val piRedMedian: Double? = null,
    val spo2Curve: String? = null,
    /** e.g. "one-point offset: R_cal = 0.3683 ... offset -2.79%", or null
     * when uncalibrated. Always label this "slope unverified" in the UI -
     * see Spo2Config's header. */
    val spo2CalibrationInfo: String? = null,
    /** QualityOverall.name() - GOOD / MARGINAL / NOT_USABLE - or null if
     * the quality report hasn't been computed yet. */
    val qualityVerdict: String? = null,
    /** The full QualityReport, serialized as JSON (six checks + values) -
     * cheap to store, avoids a second entity/join for something only ever
     * read as a whole per-reading blob. */
    val qualityJson: String? = null
)
