package com.eee312.ppgmonitor.dsp.spo2

import org.junit.Assert.assertEquals
import org.junit.Test

class Spo2FilteringTest {

    @Test
    fun `linear detrend removes a pure ramp`() {
        val x = DoubleArray(50) { 3.0 + 0.7 * it }
        val out = Spo2Filtering.linearDetrend(x)
        for (v in out) assertEquals(0.0, v, 1e-9)
    }

    @Test
    fun `linear detrend removes a constant offset`() {
        val x = DoubleArray(50) { 42.0 }
        val out = Spo2Filtering.linearDetrend(x)
        for (v in out) assertEquals(0.0, v, 1e-9)
    }

    /** Cross-checked against:
     *   from scipy.signal import butter, sosfiltfilt, detrend
     *   sos = butter(4, [0.5, 4.0], btype='bandpass', fs=25.0, output='sos')
     *   y = sosfiltfilt(sos, detrend(x))
     * on a fixed 64-sample pseudo-random vector (numpy seed 42). */
    @Test
    fun `sosFiltFilt matches scipy sosfiltfilt to 1e-9`() {
        val x = doubleArrayOf(
            -0.250920, 0.921429, 0.503988, 0.257317, -0.607963, -0.588011, -0.763833, 0.872352,
            0.362230, 0.596145, -0.758831, 1.159820, 0.904885, -0.315322, -0.356350, -0.333191,
            -0.071516, 0.389513, 0.223890, -0.037542, 0.623706, -0.301012, 0.024289, 0.192724,
            0.392140, 1.070352, -0.080652, 0.568469, 0.744829, -0.327099, 0.815090, -0.038952,
            -0.229897, 1.557771, 1.611264, 1.316795, 0.329228, -0.064656, 1.128466, 0.660305,
            0.044076, 0.810354, -0.091223, 1.678641, 0.397560, 1.225045, 0.543422, 0.980136,
            1.053421, 0.349709, 1.939169, 1.570266, 1.918998, 1.849655, 1.275800, 1.943748,
            0.296985, 0.531966, 0.250455, 0.830661, 0.977355, 0.762698, 1.897475, 0.973507
        )
        val expected = doubleArrayOf(
            -1.135424433020765e-02, 4.769895243105501e-01, 6.230990416109889e-01, 3.604025666049372e-01,
            -6.775527805436191e-02, -3.293609151549756e-01, -2.657993687637545e-01, 3.050936909051771e-02,
            3.500428270708180e-01, 5.428402860421009e-01, 5.730141133172117e-01, 4.599231257241854e-01,
            2.325967919511179e-01, -4.755134783333609e-02, -2.693867096394823e-01, -3.287859881717724e-01,
            -2.147877692511088e-01, -2.909193765695225e-02, 8.784730864510569e-02, 5.924665292737798e-02,
            -7.919451300902686e-02, -2.138870327962922e-01, -2.388250405494338e-01, -1.283768854712390e-01,
            5.214230363288500e-02, 1.965389419371971e-01, 2.212476067739834e-01, 1.010115976816558e-01,
            -1.229428613267498e-01, -3.475884567804469e-01, -4.373796317680972e-01, -2.975987247552044e-01,
            3.847083840316529e-02, 3.937227766213000e-01, 5.552428188456910e-01, 4.341330107515934e-01,
            1.269099492198620e-01, -1.820925118817529e-01, -3.698648983571196e-01, -4.284544361854932e-01,
            -4.052936547385355e-01, -3.328920186903364e-01, -2.246470657703863e-01, -1.095922028074026e-01,
            -3.961925517755642e-02, -5.294630829538464e-02, -1.332998167516805e-01, -2.057718217036314e-01,
            -1.763141105864235e-01, 8.728234514707611e-03, 3.205303723329399e-01, 6.522784596014805e-01,
            8.673428008638224e-01, 8.598807614085986e-01, 6.008689247206879e-01, 1.608727066577932e-01,
            -3.006071770938349e-01, -5.951303362680909e-01, -6.026604173207153e-01, -3.489606771396520e-01,
            -1.005801917382032e-03, 2.300505206824408e-01, 2.231299706333258e-01, 2.921322963911631e-02
        )

        val sos = Spo2FilterCoefficients.bandpassFor(25.0)
        val actual = Spo2Filtering.sosFiltFilt(sos, Spo2Filtering.linearDetrend(x))

        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], actual[i], 1e-9)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sosFiltFilt rejects a signal shorter than the required edge padding`() {
        val sos = Spo2FilterCoefficients.bandpassFor(25.0)
        Spo2Filtering.sosFiltFilt(sos, DoubleArray(5))
    }
}
