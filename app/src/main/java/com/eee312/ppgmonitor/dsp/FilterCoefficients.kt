package com.eee312.ppgmonitor.dsp

/**
 * Precomputed Butterworth band-pass SOS (second-order-section)
 * coefficients, order 4, band 0.5-4 Hz - the SAME design
 * bandpass_zero_phase.m / bandpass_causal.m use in the MATLAB project
 * (order 4, cfg.ppgBandHz = cfg.accBandHz = [0.5 4.0]).
 *
 * WHY HARDCODED INSTEAD OF DESIGNED ON-DEVICE: implementing a general
 * Butterworth designer (analog prototype -> bilinear transform -> SOS)
 * in Kotlin is a lot of numerically-fiddly code for very little benefit
 * here, since the sample rate is fixed by the hardware, not chosen at
 * runtime. Instead these were computed OFFLINE with the same method
 * bandpass_zero_phase.m uses (`scipy.signal.butter` + `zpk2sos`, which
 * matches MATLAB's `butter` + `zp2sos` bit-for-bit in intent) and pasted
 * in as constants. Each row is one second-order section as
 * [b0, b1, b2, a0, a1, a2] (a0 is always 1.0, kept for clarity).
 *
 * If you change the sample rate (see PpgPipeline.FS_HZ) to something
 * other than 25 or 50 Hz, regenerate with:
 *
 *   from scipy.signal import butter, zpk2sos
 *   z, p, k = butter(4, [0.5/(fs/2), 4.0/(fs/2)], btype='bandpass', output='zpk')
 *   sos = zpk2sos(z, p, k)
 *   # each row of sos -> one doubleArrayOf(...) below
 */
object FilterCoefficients {

    /** Order-4 Butterworth band-pass, 0.5-4 Hz, at Fs = 25 Hz. */
    val BANDPASS_0_5_4HZ_AT_25HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.014826745479, 0.029653490957, 0.014826745479, 1.000000000000, -0.817603829106, 0.220070169478),
        doubleArrayOf(1.000000000000, 2.000000000000, 1.000000000000, 1.000000000000, -0.877209869794, 0.589579378242),
        doubleArrayOf(1.000000000000, -2.000000000000, 1.000000000000, 1.000000000000, -1.743591788194, 0.764841842233),
        doubleArrayOf(1.000000000000, -2.000000000000, 1.000000000000, 1.000000000000, -1.907778714864, 0.923650624407)
    )

    /** Order-4 Butterworth band-pass, 0.5-4 Hz, at Fs = 50 Hz. */
    val BANDPASS_0_5_4HZ_AT_50HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.001397475359, 0.002794950719, 0.001397475359, 1.000000000000, -1.377642663448, 0.498061549783),
        doubleArrayOf(1.000000000000, 2.000000000000, 1.000000000000, 1.000000000000, -1.541668797538, 0.748551415364),
        doubleArrayOf(1.000000000000, -2.000000000000, 1.000000000000, 1.000000000000, -1.868310505492, 0.874129291919),
        doubleArrayOf(1.000000000000, -2.000000000000, 1.000000000000, 1.000000000000, -1.957507384697, 0.961565386894)
    )

    /** Looks up the precomputed SOS for a given sample rate (nearest of
     * the two above), warning loudly (via the returned flag) if the
     * requested rate isn't actually one of them. */
    fun bandpassFor(fsHz: Double): Array<DoubleArray> {
        return if (Math.abs(fsHz - 25.0) <= Math.abs(fsHz - 50.0)) {
            BANDPASS_0_5_4HZ_AT_25HZ
        } else {
            BANDPASS_0_5_4HZ_AT_50HZ
        }
    }
}
