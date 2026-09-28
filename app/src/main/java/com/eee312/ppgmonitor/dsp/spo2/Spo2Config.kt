package com.eee312.ppgmonitor.dsp.spo2

/** Published R -> SpO2 curves - both are GENERIC literature values, not
 * calibrated to this hardware. See estimate_spo2.m's header for why. */
enum class Spo2Curve {
    /** SpO2 = 110 - 25*R (common textbook approximation). */
    LINEAR,

    /** SpO2 = -22.53*R^2 + 1.4389*R + 103.78 (CEEMDAN-MPE / VS_LMS reference paper). */
    QUADRATIC;

    fun apply(r: Double): Double = when (this) {
        LINEAR -> 110.0 - 25.0 * r
        QUADRATIC -> -22.53 * r * r + 1.4389 * r + 103.78
    }
}

/**
 * One-point offset calibration against a reference oximeter reading.
 *
 * READ THIS BEFORE RELYING ON IT (same warning as estimate_spo2.m): a
 * single calibration point at a normal saturation makes the device read
 * "normal" BY CONSTRUCTION. It fixes the offset only - the slope stays
 * the published, unverified value - so it says nothing about whether the
 * device would correctly detect a genuine desaturation. Always label a
 * result calibrated this way as "one-point offset calibration - slope
 * unverified", never as a validated measurement.
 */
data class Spo2SinglePointCalibration(
    val enable: Boolean = false,
    val refSpo2: Double? = null,
    /** Restrict R_cal's median to this [start, end] second range (in the
     * SAME time base as Spo2Window.tSec); null = every window with a
     * usable R after warm-up. */
    val timeRangeSec: Pair<Double, Double>? = null
)

/** Mirrors config.m's cfg.spo2.* defaults exactly. */
data class Spo2Config(
    val windowSec: Double = 12.0,
    val stepSec: Double = 2.0,
    val bandHz: Pair<Double, Double> = 0.5 to 4.0,
    val warmupSec: Double = 30.0,
    val minPerfusion: Double = 0.0005,
    val validRRange: Pair<Double, Double> = 0.3 to 3.0,
    val validSpo2Range: Pair<Double, Double> = 70.0 to 100.0,
    val curve: Spo2Curve = Spo2Curve.LINEAR,
    val singlePoint: Spo2SinglePointCalibration = Spo2SinglePointCalibration(),
    // --- v3 quality gates (see docs/SPO2_METHOD.md) ---
    /** Pearson correlation of band-passed red vs IR in the window. */
    val minCorrelation: Double = 0.8,
    /** Pulse peaks in a 12 s window (40 BPM gives 8). */
    val minBeats: Int = 6,
    /** Peaks closer than this are one beat (0.27 s = 222 BPM). */
    val minBeatSpacingSec: Double = 0.27,
    /** SD of |acc| in the window above this fraction of the recording's
     * median |acc| (= gravity, any unit) is motion. */
    val maxMotionFraction: Double = 0.10,
    /** Summary SpO2 needs at least this many valid windows, else N/A. */
    val minValidWindows: Int = 3,
    /** Live rig only (null elsewhere): mean IR below this = finger off. */
    val fingerPresentDc: Double? = null,
    /** Live rig only (null elsewhere): raw at/above this = saturated. */
    val saturationCounts: Double? = null
) {
    /** Upper band edge clamped below Nyquist, same convention as
     * estimate_spo2_from_table.m's use of cfg.ppgBandHz: min(4, 0.95*fs/2). */
    fun effectiveBandHz(fsHz: Double): Pair<Double, Double> =
        bandHz.first to minOf(bandHz.second, 0.95 * fsHz / 2.0)
}
