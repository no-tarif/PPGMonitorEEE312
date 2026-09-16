package com.eee312.ppgmonitor.dsp.spo2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Confirms Spo2FilterCoefficients' hardcoded SOS matches
 * scipy.signal.butter(4, [0.5, 4.0], btype='bandpass', fs=fs, output='sos')
 * to 1e-9, for both rates the SpO2 engine supports - per Phase 2's
 * requirement. Expected values below are the literal scipy output
 * (verified independently against dsp/FilterCoefficients.kt's own 25Hz
 * constants, which were generated the same way and match exactly).
 */
class Spo2FilterCoefficientsTest {

    private val tolerance = 1e-9

    private fun assertSosMatches(expected: Array<DoubleArray>, actual: Array<DoubleArray>) {
        assertEquals(expected.size, actual.size)
        for (row in expected.indices) {
            for (col in expected[row].indices) {
                assertEquals(
                    "row $row col $col", expected[row][col], actual[row][col], tolerance
                )
            }
        }
    }

    @Test
    fun `25Hz coefficients match scipy`() {
        val expected = arrayOf(
            doubleArrayOf(1.482674547868074e-02, 2.965349095736147e-02, 1.482674547868074e-02, 1.0, -8.176038291058195e-01, 2.200701694779676e-01),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -8.772098697940495e-01, 5.895793782422942e-01),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.743591788194272e+00, 7.648418422326176e-01),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.907778714864301e+00, 9.236506244067128e-01)
        )
        assertSosMatches(expected, Spo2FilterCoefficients.bandpassFor(25.0))
        assertSosMatches(expected, Spo2FilterCoefficients.BANDPASS_0_5_4HZ_AT_25HZ)
    }

    @Test
    fun `50 over 3 Hz coefficients match scipy`() {
        val expected = arrayOf(
            doubleArrayOf(5.433413018236517e-02, 1.086682603647303e-01, 5.433413018236517e-02, 1.0, -2.650960097266856e-01, 8.265659675447572e-02),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.271093862016604e-01, 5.186445543056390e-01),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.628470804965201e+00, 6.713342332488501e-01),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.850474579468995e+00, 8.852889448576394e-01)
        )
        assertSosMatches(expected, Spo2FilterCoefficients.bandpassFor(Spo2FilterCoefficients.FS_50_3HZ))
        assertSosMatches(expected, Spo2FilterCoefficients.BANDPASS_0_5_4HZ_AT_50_3HZ)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported rate throws rather than silently approximating`() {
        Spo2FilterCoefficients.bandpassFor(50.0)
    }
}
