package com.eee312.ppgmonitor.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Digital Butterworth band-pass design as second-order sections - the
 * same steps scipy.signal.butter(N, [lo, hi], btype='bandpass', fs=fs,
 * output='sos') takes: analog prototype poles -> low-pass to band-pass
 * transform -> bilinear transform (with pre-warping) -> pairing into
 * biquads, the gain in the first section.
 *
 * Only used for sample rates that have no precomputed table (the 25 Hz /
 * 50 Hz tables in FilterCoefficients and the 25 / 16.667 Hz tables in
 * Spo2FilterCoefficients are still used as-is for those rates, so
 * existing results don't change). ButterworthDesignTest checks this
 * designer reproduces those tables to 1e-9.
 *
 * Rows are [b0, b1, b2, 1.0, a1, a2], the layout BiquadCascadeFilter and
 * Spo2Filtering expect.
 */
object ButterworthDesign {

    private data class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = C(re * k, im * k)
        operator fun div(o: C): C {
            val d = o.re * o.re + o.im * o.im
            return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        fun abs() = hypot(re, im)
        fun sqrtC(): C {
            val r = sqrt(abs())
            val th = atan2(im, re) / 2
            return C(r * cos(th), r * sin(th))
        }
    }

    fun bandpassSos(order: Int, loHz: Double, hiHz: Double, fsHz: Double): Array<DoubleArray> {
        require(order >= 2 && order % 2 == 0) { "order must be even (band-pass pairs of zeros)" }
        require(loHz > 0 && hiHz > loHz && hiHz < fsHz / 2) {
            "band $loHz-$hiHz Hz must lie inside (0, fs/2 = ${fsHz / 2})"
        }
        // Pre-warp exactly as scipy (internal fs = 2).
        val fsInt = 2.0
        val w1 = 2 * fsInt * tan(PI * (loHz / (fsHz / 2)) / fsInt)
        val w2 = 2 * fsInt * tan(PI * (hiHz / (fsHz / 2)) / fsInt)
        val bw = w2 - w1
        val wo2 = w1 * w2

        // Analog low-pass prototype poles (buttap).
        val protoPoles = (0 until order).map { i ->
            val m = -order + 1 + 2 * i
            val th = PI * m / (2 * order)
            C(-cos(th), -sin(th))
        }

        // lp2bp: each prototype pole gives two band-pass poles; `order` zeros at 0.
        val bpPoles = ArrayList<C>()
        for (p in protoPoles) {
            val pl = p * (bw / 2)
            val disc = (pl * pl - C(wo2, 0.0)).sqrtC()
            bpPoles += pl + disc
            bpPoles += pl - disc
        }
        var gain = Math.pow(bw, order.toDouble())

        // Bilinear transform (fs2 = 2 * fsInt).
        val fs2 = 2 * fsInt
        val zPoles = bpPoles.map { (C(fs2, 0.0) + it) / (C(fs2, 0.0) - it) }
        // zeros: `order` at z = +1 (from s = 0), `order` at z = -1 (from s = inf)
        var num = C(1.0, 0.0)
        repeat(order) { num = num * C(fs2, 0.0) } // prod(fs2 - 0)
        var den = C(1.0, 0.0)
        for (p in bpPoles) den = den * (C(fs2, 0.0) - p)
        gain *= (num / den).re

        // Pair conjugate poles; one biquad per pair (upper half-plane member).
        val upper = zPoles.filter { it.im > 1e-12 }.sortedBy { it.abs() }
        require(upper.size == order) { "expected $order complex-conjugate pole pairs" }

        // scipy's zpk2sos: take poles from the one closest to the unit circle
        // down, give each the nearest remaining zero pair ((z-1)^2 or
        // (z+1)^2), then emit the sections in ascending pole radius with
        // the gain folded into the first one.
        var pairsPlus = order / 2   // (z-1)^2 pairs available
        var pairsMinus = order / 2  // (z+1)^2 pairs available
        val sections = ArrayList<DoubleArray>()
        for (p in upper.sortedByDescending { it.abs() }) {
            val a1 = -2 * p.re
            val a2 = p.re * p.re + p.im * p.im
            val nearPlus = hypot(p.re - 1, p.im) <= hypot(p.re + 1, p.im)
            val usePlus = pairsPlus > 0 && (nearPlus || pairsMinus == 0)
            if (usePlus) pairsPlus-- else pairsMinus--
            val b1 = if (usePlus) -2.0 else 2.0
            sections += doubleArrayOf(1.0, b1, 1.0, 1.0, a1, a2)
        }
        val sorted = sections.sortedBy { it[5] }.toMutableList()
        val s0 = sorted[0]
        sorted[0] = doubleArrayOf(s0[0] * gain, s0[1] * gain, s0[2] * gain, 1.0, s0[4], s0[5])
        return sorted.toTypedArray()
    }
}
