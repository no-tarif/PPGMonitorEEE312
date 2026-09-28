package com.eee312.ppgmonitor.dsp

/**
 * Multichannel LMS adaptive noise canceller using accelerometer X/Y/Z
 * as the reference - Kotlin port of lms_anc_multichannel.m from the
 * MATLAB project (the 3-axis variant, which outperformed the scalar
 * accelerometer-magnitude variant in the MATLAB testing - see that
 * project's README.txt point 6 - so only this variant is ported here).
 *
 * desired   = band-passed, normalized PPG (one sample)
 * reference = band-passed, normalized [accX, accY, accZ] (one sample)
 * output    = LMS error signal = desired - (estimated motion artifact),
 *             i.e. the motion-artifact-reduced PPG
 *
 * This is INHERENTLY causal/stateful (it's an adaptive filter - it is
 * SUPPOSED to remember what it has learned), so unlike the MATLAB
 * project's original bug (resetting weights to zero on every call),
 * this class just IS the persisted state: construct it once per
 * session and keep calling process() as samples arrive.
 */
class LmsMultichannelFilter(
    private val tapsPerChannel: Int,
    private val stepSize: Double,
    private val numChannels: Int = 3
) {
    private val totalTaps = tapsPerChannel * numChannels
    private val weights = DoubleArray(totalTaps)
    // Ring-buffer-style delay line, one row per tap, one column per channel,
    // stored flattened as [tap0_ch0, tap0_ch1, tap0_ch2, tap1_ch0, ...] to
    // match lms_anc_multichannel.m's buffers(:) column-major flattening
    // closely enough for the math to be equivalent (order doesn't matter
    // as long as `weights` and `delayLine` use the SAME order consistently,
    // which they do here).
    private val delayLine = DoubleArray(totalTaps)

    /** Filters one sample. [references] must have exactly [numChannels]
     * entries (accX, accY, accZ). Returns the cleaned (artifact-reduced)
     * PPG sample. [adapt] = false freezes the weights for this sample (v3:
     * used on samples the dropout filler replaced, or whose accelerometer
     * reading was unusable, so the filter doesn't learn from made-up data).
     * With the default true this is exactly the original algorithm. */
    fun process(desired: Double, references: DoubleArray, adapt: Boolean = true): Double {
        require(references.size == numChannels)

        // Shift the delay line down by one tap (oldest tap's channels drop
        // off the end) and insert the new reference sample at the front.
        for (t in tapsPerChannel - 1 downTo 1) {
            for (c in 0 until numChannels) {
                delayLine[t * numChannels + c] = delayLine[(t - 1) * numChannels + c]
            }
        }
        for (c in 0 until numChannels) {
            delayLine[c] = references[c]
        }

        var y = 0.0
        for (i in 0 until totalTaps) {
            y += weights[i] * delayLine[i]
        }

        val e = desired - y

        if (adapt) {
            for (i in 0 until totalTaps) {
                weights[i] += stepSize * e * delayLine[i]
            }
        }

        // Instability guard, mirroring the MATLAB fix: if the filter
        // diverges, reset it instead of letting it emit NaN/garbage
        // forever - a brief burst of bad data (e.g. sensor losing skin
        // contact) shouldn't permanently kill the filter for the rest
        // of the session.
        var normSq = 0.0
        var finite = true
        for (w in weights) {
            if (!w.isFinite()) { finite = false; break }
            normSq += w * w
        }
        if (!finite || normSq > 1e12) {
            weights.fill(0.0)
            delayLine.fill(0.0)
            return desired // pass the sample through uncancelled this one time
        }

        return e
    }

    fun reset() {
        weights.fill(0.0)
        delayLine.fill(0.0)
    }
}
