package com.eee312.ppgmonitor.dsp.spo2

/**
 * Zero-phase (forward-backward) SOS filtering and linear detrend, ported
 * to match scipy.signal.sosfiltfilt / scipy.signal.detrend EXACTLY - not
 * just "filter forward then backward from rest", which would leave a
 * visible transient at both ends of the record that a naive port would
 * miss. estimate_spo2.m relies on MATLAB's own zero-phase filtfilt (odd-
 * reflection padding + steady-state initial conditions); this reproduces
 * the same behaviour via scipy's documented algorithm, which is what
 * tools/spo2_reference.py (the ground truth for this engine) actually
 * runs, so matching THAT bit for bit is what matters here.
 *
 * Kept separate from dsp/BiquadCascadeFilter.kt (Rule Zero: that file is
 * untouched) - SpO2 needs offline zero-phase filtering over a whole
 * recorded stretch, not BiquadCascadeFilter's live one-direction design.
 */
internal object Spo2Filtering {

    /** Linear least-squares detrend - matches scipy.signal.detrend(x,
     * type='linear'): fit a line against the sample index and subtract it. */
    fun linearDetrend(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return x.copyOf()
        val tMean = (n - 1) / 2.0
        val xMean = x.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dt = i - tMean
            num += dt * (x[i] - xMean)
            den += dt * dt
        }
        val slope = if (den != 0.0) num / den else 0.0
        val intercept = xMean - slope * tMean
        return DoubleArray(n) { x[it] - (intercept + slope * it) }
    }

    /** scipy.signal.sosfiltfilt(sos, x) with default padtype='odd' and
     * default padlen (3*ntaps). */
    fun sosFiltFilt(sos: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        val nSections = sos.size
        var ntaps = 2 * nSections + 1
        val b2ZeroCount = sos.count { it[2] == 0.0 }
        val a2ZeroCount = sos.count { it[5] == 0.0 }
        ntaps -= minOf(b2ZeroCount, a2ZeroCount)
        val edge = ntaps * 3

        require(edge < x.size) {
            "sosFiltFilt: signal too short (${x.size} samples) for the required " +
                "edge padding of $edge samples"
        }

        val ext = oddExt(x, edge)
        val zi = sosZi(sos)

        val x0 = ext[0]
        val forward = sosFilt(sos, ext, scaleZi(zi, x0))

        val reversedForward = forward.reversedArray()
        val y0 = reversedForward[0]
        val backward = sosFilt(sos, reversedForward, scaleZi(zi, y0))

        val result = backward.reversedArray()
        return result.copyOfRange(edge, result.size - edge)
    }

    private fun scaleZi(zi: Array<DoubleArray>, x0: Double): Array<DoubleArray> =
        Array(zi.size) { s -> doubleArrayOf(zi[s][0] * x0, zi[s][1] * x0) }

    /** scipy's odd_ext: reflects each end through the boundary sample
     * (2*x[0]-x[n..1] before, 2*x[-1]-x[-2..-(n+1)] after), continuing the
     * signal's local slope through the join rather than mirroring it -
     * avoids the discontinuity a plain mirror/repeat would introduce. */
    internal fun oddExt(x: DoubleArray, n: Int): DoubleArray {
        val len = x.size
        require(n in 1 until len) { "oddExt: edge $n must be in [1, ${len - 1}]" }
        val out = DoubleArray(len + 2 * n)
        val x0 = x[0]
        val xLast = x[len - 1]
        for (i in 0 until n) {
            out[i] = 2 * x0 - x[n - i]
        }
        System.arraycopy(x, 0, out, n, len)
        for (i in 0 until n) {
            out[n + len + i] = 2 * xLast - x[len - 2 - i]
        }
        return out
    }

    /** Per-section steady-state initial conditions for a unit step input,
     * cascaded with the running DC gain of preceding sections - matches
     * scipy.signal.sosfilt_zi. Closed form for a direct-form-II-
     * transposed biquad (a0=1), derived by requiring the state to be
     * unchanged when the input is a constant 1 forever:
     *   y  = (b0+b1+b2) / (1+a1+a2)     [= the section's DC gain]
     *   z1 = (b1+b2) - (a1+a2)*y
     *   z2 =  b2      -  a2   *y
     */
    internal fun sosZi(sos: Array<DoubleArray>): Array<DoubleArray> {
        var scale = 1.0
        return Array(sos.size) { s ->
            val b0 = sos[s][0]; val b1 = sos[s][1]; val b2 = sos[s][2]
            val a1 = sos[s][4]; val a2 = sos[s][5] // a0 == 1
            val dcGain = (b0 + b1 + b2) / (1.0 + a1 + a2)
            val z1 = scale * ((b1 + b2) - (a1 + a2) * dcGain)
            val z2 = scale * (b2 - a2 * dcGain)
            scale *= dcGain
            doubleArrayOf(z1, z2)
        }
    }

    /** Direct-form-II-transposed SOS cascade with an explicit per-section
     * initial state (unlike dsp/BiquadCascadeFilter.kt, which always
     * starts from zero) - needed to reproduce scipy's sosfilt(..., zi=). */
    internal fun sosFilt(sos: Array<DoubleArray>, x: DoubleArray, zi: Array<DoubleArray>): DoubleArray {
        val z1 = DoubleArray(sos.size) { zi[it][0] }
        val z2 = DoubleArray(sos.size) { zi[it][1] }
        val y = DoubleArray(x.size)
        for (n in x.indices) {
            var v = x[n]
            for (s in sos.indices) {
                val b0 = sos[s][0]; val b1 = sos[s][1]; val b2 = sos[s][2]
                val a1 = sos[s][4]; val a2 = sos[s][5]
                val out = b0 * v + z1[s]
                z1[s] = b1 * v - a1 * out + z2[s]
                z2[s] = b2 * v - a2 * out
                v = out
            }
            y[n] = v
        }
        return y
    }
}
