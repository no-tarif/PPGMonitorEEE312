package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.dsp.spo2.Spo2FilterCoefficients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** ButterworthDesign must reproduce scipy.signal.butter(4, [0.5, 4.0],
 * btype='bandpass', fs=fs, output='sos') - the same design every
 * precomputed table in the app came from. */
class ButterworthDesignTest {

    private fun assertSos(expected: Array<DoubleArray>, actual: Array<DoubleArray>, tol: Double = 1e-9) {
        assertEquals(expected.size, actual.size)
        for (s in expected.indices) for (c in 0 until 6) {
            assertEquals("section $s coef $c", expected[s][c], actual[s][c], tol)
        }
    }

    @Test
    fun `reproduces the existing 25 Hz, 50 Hz and 50 over 3 Hz tables`() {
        assertSos(Spo2FilterCoefficients.BANDPASS_0_5_4HZ_AT_25HZ, ButterworthDesign.bandpassSos(4, 0.5, 4.0, 25.0))
        assertSos(FilterCoefficients.BANDPASS_0_5_4HZ_AT_50HZ, ButterworthDesign.bandpassSos(4, 0.5, 4.0, 50.0), 1e-11)
        assertSos(
            Spo2FilterCoefficients.BANDPASS_0_5_4HZ_AT_50_3HZ,
            ButterworthDesign.bandpassSos(4, 0.5, 4.0, Spo2FilterCoefficients.FS_50_3HZ)
        )
    }

    @Test
    fun `matches scipy at rates with no table`() {
        val scipy = mapOf(
            20.0 to arrayOf(
                doubleArrayOf(0.030485269849154187, 0.060970539698308374, 0.030485269849154187, 1.0, -0.5431821824862358, 0.1354263190936303),
                doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.507420899090837, 0.5432909615041782),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.6846147291414004, 0.7161294156396198),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.880089527696397, 0.9045868431052028)
            ),
            24.8 to arrayOf(
                doubleArrayOf(0.015221190032707483, 0.030442380065414967, 0.015221190032707483, 1.0, -0.8087553369852899, 0.21683020487853782),
                doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.8655442033951837, 0.5877469863727571),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.7416499092842719, 0.7632093334554649),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9069152913369505, 0.9230380465629567)
            ),
            30.0 to arrayOf(
                doubleArrayOf(0.008114715950793936, 0.016229431901587873, 0.008114715950793936, 1.0, -1.0011374769931893, 0.29511561301285805),
                doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.112940511263808, 0.6325749386459433),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.7842568607944052, 0.7994974141394621),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9251826809456876, 0.9362907049803838)
            ),
            100.0 to arrayOf(
                doubleArrayOf(0.00011138107559506853, 0.00022276215119013706, 0.00011138107559506853, 1.0, -1.6766954582488076, 0.7108019089468294),
                doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.806759060864077, 0.8625897096353131),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.933393151192315, 0.9349063027928723),
                doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9796314851310104, 0.9806565775245344)
            )
        )
        for ((fs, expected) in scipy) assertSos(expected, ButterworthDesign.bandpassSos(4, 0.5, 4.0, fs), 1e-10)
    }

    @Test
    fun `pipeline keeps its 25 Hz table object, designs only for other rates`() {
        assertSame(FilterCoefficients.BANDPASS_0_5_4HZ_AT_25HZ, FilterCoefficients.bandpassFor(25.0))
        assertSame(FilterCoefficients.BANDPASS_0_5_4HZ_AT_50HZ, FilterCoefficients.bandpassFor(50.0))
        assertSos(ButterworthDesign.bandpassSos(4, 0.5, 4.0, 24.8), FilterCoefficients.bandpassFor(24.8), 0.0)
    }
}
