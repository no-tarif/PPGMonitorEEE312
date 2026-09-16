package com.eee312.ppgmonitor.dsp.spo2

/**
 * Precomputed Butterworth band-pass SOS coefficients for the SpO2 engine,
 * order 4, band 0.5-4 Hz - same design as dsp/FilterCoefficients.kt, but
 * kept SEPARATE from it per Rule Zero (that file is untouchable for this
 * project; SpO2 is an addition, never a change, to the existing pipeline).
 *
 * Generated with:
 *   from scipy.signal import butter
 *   sos = butter(4, [0.5, 4.0], btype='bandpass', fs=fs, output='sos')
 *
 * Two rates are provided: 25 Hz is the only rate the app ever actually
 * uses for SpO2 (every dataset and live source runs at PpgPipeline.FS_HZ
 * = 25.0). 16.667 Hz exists only so JVM tests can cross-check the sep16
 * capture's NATIVE rate against tools/spo2_reference.py's (a) branch -
 * see Spo2FilterCoefficientsTest, which asserts these match scipy's
 * output to 1e-9.
 */
object Spo2FilterCoefficients {

    /** Order-4 Butterworth band-pass, 0.5-4 Hz, at Fs = 25 Hz. */
    val BANDPASS_0_5_4HZ_AT_25HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.014826745478680743, 0.029653490957361474, 0.014826745478680743, 1.0, -0.8176038291058195, 0.2200701694779676),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.8772098697940495, 0.5895793782422942),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.743591788194272, 0.7648418422326176),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.907778714864301, 0.9236506244067128)
    )

    /** Order-4 Butterworth band-pass, 0.5-4 Hz, at Fs = 50/3 Hz exactly
     * (16.666666666666668... - sep16_capture.csv's native rate: constant
     * 0.06s sample interval). */
    val BANDPASS_0_5_4HZ_AT_50_3HZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.05433413018236517, 0.1086682603647303, 0.05433413018236517, 1.0, -0.2650960097266856, 0.08265659675447572),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.1271093862016604, 0.518644554305639),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.628470804965201, 0.6713342332488501),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.850474579468995, 0.8852889448576394)
    )

    const val FS_50_3HZ: Double = 50.0 / 3.0

    /** Strict lookup (unlike dsp/FilterCoefficients.bandpassFor's
     * nearest-neighbour tolerance) - SpO2's PI/R numbers are sensitive to
     * using the exact rate the data was actually sampled/resampled at, so
     * silently snapping to the "nearest" supported rate would be a
     * correctness bug here, not a convenience. */
    fun bandpassFor(fsHz: Double): Array<DoubleArray> = when {
        Math.abs(fsHz - 25.0) < 1e-6 -> BANDPASS_0_5_4HZ_AT_25HZ
        Math.abs(fsHz - FS_50_3HZ) < 1e-6 -> BANDPASS_0_5_4HZ_AT_50_3HZ
        else -> throw IllegalArgumentException(
            "No precomputed SpO2 band-pass SOS for fs=$fsHz Hz - only 25.0 and " +
                "${FS_50_3HZ} (50/3) are supported. Generate new coefficients with " +
                "scipy.signal.butter(4, [0.5, 4.0], btype='bandpass', fs=$fsHz, output='sos') " +
                "and add them here."
        )
    }
}
