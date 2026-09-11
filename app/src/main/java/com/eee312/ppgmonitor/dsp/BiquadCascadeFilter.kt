package com.eee312.ppgmonitor.dsp

/**
 * Causal (one-direction) IIR filter applied as a cascade of second-
 * order sections (SOS), each in Direct-Form-II-Transposed form with
 * persisted per-section state. This is the Kotlin equivalent of
 * bandpass_causal.m: same SOS design as the MATLAB project's
 * bandpass_zero_phase.m, but filtered in ONE direction (this is a live
 * app processing samples as they arrive - there is no "future" data to
 * filter backward through, the way filtfilt does offline).
 *
 * Trade-off vs. zero-phase filtering: this filter has a small,
 * frequency-dependent group delay. That's expected and fine for a live
 * heart-rate monitor - it doesn't affect the estimated BPM, which only
 * depends on periodicity, not exact timing alignment.
 */
class BiquadCascadeFilter(private val sos: Array<DoubleArray>) {

    // Per-section Direct-Form-II-Transposed state: two delay elements each.
    private val z1 = DoubleArray(sos.size)
    private val z2 = DoubleArray(sos.size)

    /** Filters one sample, updating internal state for the next call. */
    fun process(xIn: Double): Double {
        var x = xIn
        for (s in sos.indices) {
            val b0 = sos[s][0]; val b1 = sos[s][1]; val b2 = sos[s][2]
            val a1 = sos[s][4]; val a2 = sos[s][5] // a0 is always 1.0

            val y = b0 * x + z1[s]
            z1[s] = b1 * x - a1 * y + z2[s]
            z2[s] = b2 * x - a2 * y

            x = y
        }
        return x
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }
}
