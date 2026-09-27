package com.eee312.ppgmonitor.dsp

/**
 * Measures a stream's real sample rate from its timestamps.
 *
 * [addLive]: least-squares slope of arrival time against sample slot
 * (the firmware's seq number when there is one, else the running count).
 * USB-serial delivers lines in bursts, so single intervals are useless
 * (0 ms, then 80 ms...), but the slope over many seconds is accurate to
 * well under 0.1 %.
 *
 * [addRecorded]: median of positive time steps - for a file's own time
 * column, robust to a single corrupted timestamp (hw_class1 has one
 * +/-387 s glitch) and to loop restarts. A reservoir of up to
 * [MAX_DT] steps keeps memory bounded on 12-hour looped sessions.
 */
class SampleRateEstimator {
    companion object {
        private const val MAX_DT = 20_000
    }

    // Regression state, relative to the first point to keep sums small.
    private var n = 0L
    private var x0 = 0.0
    private var t0 = 0.0
    private var sx = 0.0
    private var st = 0.0
    private var sxx = 0.0
    private var sxt = 0.0

    private val dts = DoubleArray(MAX_DT)
    private var dtCount = 0
    private var dtSeen = 0L
    private var lastT = Double.NaN
    private var rng = 12345L

    val count: Long get() = n

    fun addLive(slot: Long, timeSec: Double) {
        if (n == 0L) { x0 = slot.toDouble(); t0 = timeSec }
        val x = slot - x0
        val t = timeSec - t0
        n++
        sx += x; st += t; sxx += x * x; sxt += x * t
    }

    /** Samples per second from the regression, or null until there are
     * [minSamples] points spanning at least 2 s. */
    fun liveRateHz(minSamples: Int = 50): Double? {
        if (n < minSamples) return null
        val den = n * sxx - sx * sx
        if (den <= 0.0) return null
        val secPerSample = (n * sxt - sx * st) / den
        if (secPerSample <= 0.0) return null
        if (sxx / n < 1.0) return null
        val span = secPerSample * (sx / n) * 2
        if (span < 2.0) return null
        return 1.0 / secPerSample
    }

    fun addRecorded(timeSec: Double) {
        val prev = lastT
        lastT = timeSec
        if (prev.isNaN() || timeSec.isNaN()) return
        val dt = timeSec - prev
        if (dt <= 0.0) return
        dtSeen++
        if (dtCount < MAX_DT) {
            dts[dtCount++] = dt
        } else {
            rng = rng * 6364136223846793005L + 1442695040888963407L
            val j = ((rng ushr 1) % dtSeen).toInt()
            if (j < MAX_DT) dts[j] = dt
        }
    }

    fun recordedRateHz(): Double? {
        if (dtCount < 10) return null
        val sorted = dts.copyOf(dtCount).also { it.sort() }
        val mid = dtCount / 2
        val med = if (dtCount % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        return if (med > 0) 1.0 / med else null
    }
}
