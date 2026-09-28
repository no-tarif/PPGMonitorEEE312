package com.eee312.ppgmonitor.dsp.spo2

/** Why a window's SpO2 wasn't accepted (or, for NONE, that it was). */
enum class Spo2RejectReason {
    NONE,
    /** Either channel's mean raw (DC) level was <= 0 in this window. */
    NON_POSITIVE_DC,
    /** Either channel's perfusion index (AC/DC) was below cfg.minPerfusion. */
    LOW_PERFUSION,
    /** R = PI_red/PI_ir fell outside cfg.validRRange. */
    R_OUT_OF_RANGE,
    /** Curve output (after any calibration) exceeded cfg.validSpo2Range's upper bound. */
    ABOVE_100,
    /** Curve output (after any calibration) fell below cfg.validSpo2Range's lower bound. */
    BELOW_70,
    // --- v3 quality gates (appended: this enum is stored by name) ---
    /** The window holds filled / lost / settling samples (dropout filler). */
    DROPOUT,
    /** A channel reached the ADC rail in this window. */
    SATURATED,
    /** Mean IR below the finger-present level (live rig only). */
    FINGER_OFF,
    /** Band-passed red and IR don't move together (corr < cfg.minCorrelation). */
    LOW_CORRELATION,
    /** Accelerometer shows real movement during the window. */
    MOTION,
    /** Fewer than cfg.minBeats pulse peaks in the window. */
    TOO_FEW_BEATS
}

/**
 * One analysis window's result. [r], [spo2], [piIr], [piRed] are
 * Double.NaN when not computed for that window (e.g. DC<=0 means PI/R/
 * SpO2 never get computed at all; R out of range means SpO2 never gets
 * computed but R itself is still kept - "R is kept even if it is then
 * rejected", per estimate_spo2.m).
 */
data class Spo2Window(
    val tSec: Double,
    val r: Double,
    val spo2: Double,
    val piIr: Double,
    val piRed: Double,
    val valid: Boolean,
    val rejectReason: Spo2RejectReason
)

data class Spo2Summary(
    val windows: Int,
    val accepted: Int,
    val acceptedPct: Double,
    /** Median of the valid windows; null when fewer than
     * Spo2Config.minValidWindows are valid (shown as N/A). */
    val medianAcceptedSpo2: Double?,
    val minAcceptedSpo2: Double?,
    val maxAcceptedSpo2: Double?,
    /** Over every window with a finite R (i.e. that got past the DC/
     * perfusion checks), regardless of accept/reject - unaffected by
     * calibration, since calibration shifts SpO2, not R. NaN if none. */
    val medianR: Double,
    val sdR: Double,
    val medianPiIr: Double,
    val medianPiRed: Double,
    val curve: Spo2Curve,
    val calibrationInfo: String,
    val warmupSecUsed: Double,
    /** Plain-language explanation of what the numbers above mean,
     * reproducing (and in the ABOVE_100-dominant case, sharpening)
     * estimate_spo2.m's printed warnings - see Spo2Engine.buildDiagnosis. */
    val diagnosis: String,
    /** v3: windows whose curve value was above 100 % (rejected, not hidden:
     * they stay in the window table with their raw value), and the highest. */
    val above100Count: Int = 0,
    val maxRawSpo2: Double = Double.NaN
)

sealed class Spo2Result {
    data class Available(val windows: List<Spo2Window>, val summary: Spo2Summary) : Spo2Result()

    /** SpO2 could not be computed at all - single wavelength, or the
     * recording is shorter than one window after the warm-up skip. Never
     * synthesise a result in this case; show the reason instead. */
    data class NotAvailable(val reason: String) : Spo2Result()
}
