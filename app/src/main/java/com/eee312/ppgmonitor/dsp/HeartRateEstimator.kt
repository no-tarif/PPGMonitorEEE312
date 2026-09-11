package com.eee312.ppgmonitor.dsp

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Windowed FFT-based heart-rate estimation - Kotlin port of
 * estimate_hr_fft.m from the MATLAB project, including its
 * second-harmonic correction. Where the MATLAB version processes a
 * whole recorded signal at once and returns a table of estimates (one
 * per sliding window), this version is built for a LIVE app: feed it
 * one cleaned-PPG sample at a time via [addSample], and it returns a
 * new BPM estimate every [stepSec] seconds once at least [windowSec]
 * seconds of data have accumulated (returns null in between - keep
 * showing the previous value).
 *
 * @param fsHz same rate the PPG pipeline runs at (PpgPipeline.FS_HZ)
 * @param windowSec analysis window length, seconds (MATLAB default: 12)
 * @param stepSec how often a new estimate is produced, seconds (MATLAB default: 2)
 * @param hrRangeBpm physiologically valid range (MATLAB default: 30-240)
 * @param harmonicPowerRatio see estimate_hr_fft.m's header - 0.70 was
 *   empirically tuned on the PhysioNet dataset in the MATLAB project;
 *   RE-TUNE this once you have real hardware + a reference HR to check
 *   against (see HARDWARE_DATA_NOTES.txt in the MATLAB project for why
 *   a reference matters).
 */
class HeartRateEstimator(
    private val fsHz: Double,
    private val windowSec: Double = 12.0,
    private val stepSec: Double = 2.0,
    private val hrRangeBpm: Pair<Double, Double> = 30.0 to 240.0,
    private val harmonicPowerRatio: Double = 0.70
) {
    private val windowSamples = Math.round(windowSec * fsHz).toInt()
    private val stepSamples = Math.round(stepSec * fsHz).toInt().coerceAtLeast(1)

    private val buffer = ArrayDeque<Double>(windowSamples)
    private var samplesSinceLastEstimate = 0

    /** Latest known SQI (0..1, higher = cleaner/sharper spectral peak) -
     * a reference-free quality signal, same idea as estimate_hr_fft.m's
     * `sqi` output column, useful later for an on-screen "signal
     * quality" indicator (see validate_ppg_quality.m in the MATLAB
     * project for how this gets used to gate whether a recording is
     * trustworthy at all). */
    var lastSqi: Double = Double.NaN
        private set

    /** Feed one new cleaned-PPG sample. Returns a new BPM estimate when
     * one becomes available (every stepSec once the window is full),
     * otherwise null - keep displaying the previous non-null value. */
    fun addSample(x: Double): Double? {
        buffer.addLast(x)
        while (buffer.size > windowSamples) buffer.removeFirst()

        samplesSinceLastEstimate++
        if (buffer.size < windowSamples || samplesSinceLastEstimate < stepSamples) {
            return null
        }
        samplesSinceLastEstimate = 0

        return estimateFromWindow(buffer.toDoubleArray())
    }

    private fun estimateFromWindow(segIn: DoubleArray): Double? {
        val n = segIn.size
        val mean = segIn.average()
        val seg = DoubleArray(n) { (segIn[it] - mean) * hann(it, n) }

        val nfft = SimpleFFT.nextPowerOfTwo(n * 8)
        val real = DoubleArray(nfft)
        val imag = DoubleArray(nfft)
        System.arraycopy(seg, 0, real, 0, n)

        SimpleFFT.fft(real, imag)

        val nHalf = nfft / 2 + 1
        val power = DoubleArray(nHalf) { real[it] * real[it] + imag[it] * imag[it] }
        val freqOf = { k: Int -> k * fsHz / nfft }

        val fMin = hrRangeBpm.first / 60.0
        val fMax = hrRangeBpm.second / 60.0

        var bandPower = 0.0
        var bestK = -1
        var bestP = -1.0
        for (k in 0 until nHalf) {
            val f = freqOf(k)
            if (f in fMin..fMax) {
                bandPower += power[k]
                if (power[k] > bestP) {
                    bestP = power[k]
                    bestK = k
                }
            }
        }
        if (bestK < 0) return null

        var k = bestK
        val dominantFreq = freqOf(k)
        val dominantPower = power[k]

        // Second-harmonic correction (see estimate_hr_fft.m's header for
        // the worked example: true HR ~98 BPM mistaken for ~196 BPM).
        val candidateFundamental = dominantFreq / 2.0
        if (candidateFundamental >= fMin) {
            val tol = 0.15
            var halfK = -1
            var halfP = -1.0
            for (kk in 0 until nHalf) {
                val f = freqOf(kk)
                if (f in (candidateFundamental - tol)..(candidateFundamental + tol)) {
                    if (power[kk] > halfP) {
                        halfP = power[kk]
                        halfK = kk
                    }
                }
            }
            if (halfK >= 0 && halfP >= harmonicPowerRatio * dominantPower) {
                k = halfK
            }
        }

        // Parabolic interpolation around the selected peak for sub-bin accuracy.
        var delta = 0.0
        if (k > 0 && k < nHalf - 1) {
            val alpha = power[k - 1]
            val beta = power[k]
            val gamma = power[k + 1]
            val denom = alpha - 2 * beta + gamma
            if (Math.abs(denom) > 1e-12) {
                delta = 0.5 * (alpha - gamma) / denom
                delta = max(-1.0, min(1.0, delta))
            }
        }

        val df = fsHz / nfft
        val fPeak = freqOf(k) + delta * df
        val hrCandidate = 60.0 * fPeak

        // Reference-free signal-quality index: fraction of in-band power
        // within +-0.1 Hz of the selected peak.
        if (bandPower > 0) {
            var nearPower = 0.0
            for (kk in 0 until nHalf) {
                val f = freqOf(kk)
                if (f in fMin..fMax && f in (fPeak - 0.1)..(fPeak + 0.1)) {
                    nearPower += power[kk]
                }
            }
            lastSqi = nearPower / bandPower
        }

        return if (hrCandidate in hrRangeBpm.first..hrRangeBpm.second) hrCandidate else null
    }

    private fun hann(i: Int, n: Int): Double =
        if (n <= 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))

    fun reset() {
        buffer.clear()
        samplesSinceLastEstimate = 0
        lastSqi = Double.NaN
    }
}
