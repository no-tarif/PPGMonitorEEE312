package com.eee312.ppgmonitor.dsp.quality

/** PASS/WARN/FAIL per check, or NOT_APPLICABLE for check 6 when there is
 * no accelerometer signal to compare against (e.g. "Hardware - Running 2
 * (single lambda)", whose accMagnitude is all zero - see the Phase 0
 * report on why). NOT_APPLICABLE counts toward neither pass nor fail. */
enum class QualityVerdict { PASS, WARN, FAIL, NOT_APPLICABLE }

enum class QualityOverall { GOOD, MARGINAL, NOT_USABLE }

data class QualityCheck(
    val name: String,
    val verdict: QualityVerdict,
    /** Human-readable one-line summary, e.g. "54.0% of in-band power in
     * the cardiac band (0.9-2.5 Hz)" - mirrors validate_ppg_quality.m's
     * fprintf lines closely enough to show in a UI table. */
    val detail: String
)

data class QualityReport(
    val checks: List<QualityCheck>, // exactly 6, in validate_ppg_quality.m's order
    val cardiacFraction: Double,
    val lowFraction: Double,
    val dominantFreqHz: Double,
    val dominantBpm: Double,
    val meanSqi: Double,
    val nWindows: Int,
    val medianHrBpm: Double,
    val iqrHrBpm: Double,
    val medianCadenceBpm: Double,      // NaN when check 6 is N/A
    val cadenceSeparationBpm: Double,  // NaN when check 6 is N/A
    val overall: QualityOverall
)
