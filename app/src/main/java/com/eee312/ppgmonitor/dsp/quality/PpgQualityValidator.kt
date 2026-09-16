package com.eee312.ppgmonitor.dsp.quality

import com.eee312.ppgmonitor.dsp.HeartRateEstimator
import com.eee312.ppgmonitor.dsp.SimpleFFT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Port of validate_ppg_quality.m: six reference-free checks that a
 * recording actually contains a usable pulse, run AFTER a session ends
 * (not live). See that file's header for the full rationale - short
 * version: every downstream number (HR, SpO2, filter comparisons) is
 * meaningless if the PPG never had a detectable pulse, and that can't be
 * assumed on hardware the way it can on a public dataset.
 *
 * DEVIATION FROM MATLAB (checks 2, 3, 5): validate_ppg_quality.m calls
 * estimate_hr_fft AGAIN on the PPG channel purely to get a per-window
 * HR/SQI series for these three checks. This port instead takes the
 * app's OWN per-window BPM/SQI as already produced live by
 * HeartRateEstimator during the session (see [hrBpmPerWindow] /
 * [sqiPerWindow]) - running a second, separate HR estimator over the
 * same signal would be redundant and could disagree with the number the
 * user actually saw on screen. Check 6 (pulse vs. cadence) still needs a
 * cadence series that doesn't exist anywhere else in the app, so it
 * reuses [HeartRateEstimator] UNMODIFIED, fed the accelerometer
 * magnitude instead of PPG - its "find the dominant periodicity in a
 * physiologically-plausible per-minute range" logic applies equally to
 * step cadence.
 *
 * Does not touch PpgPipeline, HeartRateEstimator, or any other Rule-
 * Zero-protected class.
 */
object PpgQualityValidator {

    private const val CARDIAC_LO_HZ = 0.9   // 54 BPM
    private const val CARDIAC_HI_HZ = 2.5   // 150 BPM
    private const val WELCH_SEGMENT_SEC = 30.0
    private const val ACC_ZERO_EPS = 1e-9

    fun validate(
        bandpassedPpg: DoubleArray,
        accMagnitude: DoubleArray,
        fsHz: Double,
        /** The app's own per-window HR estimates for this session, in
         * order, ALREADY filtered to the windows where an estimate was
         * produced (i.e. no nulls) - e.g. SampleEntity.bpm's non-null
         * values in tSec order. */
        hrBpmPerWindow: List<Double>,
        /** HeartRateEstimator.lastSqi captured at each of the same steps
         * as [hrBpmPerWindow] (same length, same order). */
        sqiPerWindow: List<Double>,
        ppgBandHz: Pair<Double, Double> = 0.5 to 4.0
    ): QualityReport {
        val (freqs, psd) = welchPsd(bandpassedPpg, fsHz)
        val bandIdx = freqs.indices.filter { freqs[it] in ppgBandHz.first..ppgBandHz.second }
        val bandPower = bandIdx.sumOf { psd[it] }

        // MATLAB throws an error here ("PPG has no in-band power at
        // all"). A report tool should never crash on bad input - treat
        // it as every dependent check failing instead.
        val cardiacFraction: Double
        val lowFraction: Double
        val dominantFreqHz: Double
        val dominantBpm: Double
        if (bandPower > 0.0) {
            val cardIdx = bandIdx.filter { freqs[it] in CARDIAC_LO_HZ..CARDIAC_HI_HZ }
            val lowIdx = bandIdx.filter { freqs[it] < CARDIAC_LO_HZ }
            cardiacFraction = cardIdx.sumOf { psd[it] } / bandPower
            lowFraction = lowIdx.sumOf { psd[it] } / bandPower
            var bestK = -1
            var bestP = -1.0
            for (k in bandIdx) if (psd[k] > bestP) { bestP = psd[k]; bestK = k }
            dominantFreqHz = if (bestK >= 0) freqs[bestK] else Double.NaN
            dominantBpm = if (bestK >= 0) 60.0 * freqs[bestK] else Double.NaN
        } else {
            cardiacFraction = Double.NaN
            lowFraction = Double.NaN
            dominantFreqHz = Double.NaN
            dominantBpm = Double.NaN
        }

        val meanSqi = sqiPerWindow.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }

        val sortedHr = hrBpmPerWindow.filter { it.isFinite() }.sorted()
        val medianHr = median(sortedHr)
        val iqrHr = if (sortedHr.size >= 2) percentile(sortedHr, 0.75) - percentile(sortedHr, 0.25) else Double.NaN

        // --- Check 6: accelerometer cadence, via HeartRateEstimator reused unmodified ---
        val accRms = sqrt(accMagnitude.sumOf { it * it } / accMagnitude.size.coerceAtLeast(1))
        val accIsZero = accMagnitude.isEmpty() || accRms < ACC_ZERO_EPS

        val medianCadence: Double
        val cadenceSeparation: Double
        val v6: QualityVerdict
        if (accIsZero) {
            medianCadence = Double.NaN
            cadenceSeparation = Double.NaN
            v6 = QualityVerdict.NOT_APPLICABLE
        } else {
            val cadenceEstimator = HeartRateEstimator(fsHz)
            val cadences = ArrayList<Double>()
            for (v in accMagnitude) cadenceEstimator.addSample(v)?.let { cadences += it }
            medianCadence = median(cadences.sorted())
            cadenceSeparation = if (medianHr.isFinite() && medianCadence.isFinite()) {
                kotlin.math.abs(medianHr - medianCadence)
            } else Double.NaN
            v6 = verdict(cadenceSeparation, 8.0, 3.0)
        }

        val v1 = verdict(cardiacFraction, 0.55, 0.40)
        val v2 = verdict(meanSqi, 0.45, 0.30)
        val v3 = verdict(-iqrHr, -12.0, -25.0)
        val v4 = verdict(-lowFraction, -0.15, -0.30)
        val v5 = when {
            !medianHr.isFinite() -> QualityVerdict.FAIL
            medianHr in 50.0..180.0 -> QualityVerdict.PASS
            medianHr in 45.0..200.0 -> QualityVerdict.WARN
            else -> QualityVerdict.FAIL
        }

        val checks = listOf(
            QualityCheck(
                "Cardiac-band dominance", v1,
                "%.1f%% of in-band power falls in the cardiac band (%.1f-%.1f Hz)"
                    .format(100 * cardiacFraction, CARDIAC_LO_HZ, CARDIAC_HI_HZ)
            ),
            QualityCheck(
                "Spectral peak sharpness", v2,
                "mean SQI %.3f over %d window(s) (app's own per-window value)".format(meanSqi, sqiPerWindow.size)
            ),
            QualityCheck(
                "Heart-rate stability", v3,
                "IQR %.1f BPM over %d window(s)".format(iqrHr, sortedHr.size)
            ),
            QualityCheck(
                "Baseline-drift dominance", v4,
                "%.1f%% of in-band power below %.1f Hz (%.0f BPM)".format(100 * lowFraction, CARDIAC_LO_HZ, 60 * CARDIAC_LO_HZ)
            ),
            QualityCheck(
                "Physiological plausibility", v5,
                "median HR %.1f BPM".format(medianHr)
            ),
            QualityCheck(
                "Pulse vs. cadence separation", v6,
                if (v6 == QualityVerdict.NOT_APPLICABLE) {
                    "N/A - accelerometer signal is all zero for this recording"
                } else {
                    "HR %.1f BPM vs. cadence %.1f/min (separation %.1f)".format(medianHr, medianCadence, cadenceSeparation)
                }
            )
        )

        val nFail = checks.count { it.verdict == QualityVerdict.FAIL }
        val nWarn = checks.count { it.verdict == QualityVerdict.WARN }
        val overall = when {
            nFail > 0 -> QualityOverall.NOT_USABLE
            nWarn > 0 -> QualityOverall.MARGINAL
            else -> QualityOverall.GOOD
        }

        return QualityReport(
            checks = checks,
            cardiacFraction = cardiacFraction,
            lowFraction = lowFraction,
            dominantFreqHz = dominantFreqHz,
            dominantBpm = dominantBpm,
            meanSqi = meanSqi,
            nWindows = sortedHr.size,
            medianHrBpm = medianHr,
            iqrHrBpm = iqrHr,
            medianCadenceBpm = medianCadence,
            cadenceSeparationBpm = cadenceSeparation,
            overall = overall
        )
    }

    private fun verdict(value: Double, goodThr: Double, warnThr: Double): QualityVerdict {
        if (!value.isFinite()) return QualityVerdict.FAIL
        return when {
            value >= goodThr -> QualityVerdict.PASS
            value >= warnThr -> QualityVerdict.WARN
            else -> QualityVerdict.FAIL
        }
    }

    /** Linear-interpolated percentile of an ALREADY SORTED list, same
     * method as validate_ppg_quality.m's quantile_simple (equivalent to
     * numpy's default 'linear' interpolation). */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val n = sorted.size
        if (n == 0) return Double.NaN
        if (n == 1) return sorted[0]
        val pos = (n - 1) * p
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return if (lo == hi) sorted[lo] else sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
    }

    private fun median(sorted: List<Double>): Double = percentile(sorted, 0.5)

    /** Welch PSD: Hann window (symmetric, same formula as
     * HeartRateEstimator's own hann()), 30s segments, 50% overlap,
     * one-sided. Ratios of sums of this PSD (cardiacFraction,
     * lowFraction) are invariant to the overall scale constant, so exact
     * agreement with MATLAB's pwelch scaling doesn't matter for checks 1
     * and 4 - only relative band power does. */
    private fun welchPsd(x: DoubleArray, fsHz: Double): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val segLen = minOf(n, Math.round(WELCH_SEGMENT_SEC * fsHz).toInt()).coerceAtLeast(1)
        val hop = maxOf(1, segLen / 2)
        val nfft = SimpleFFT.nextPowerOfTwo(segLen)
        val nHalf = nfft / 2 + 1

        val window = DoubleArray(segLen) { i -> if (segLen <= 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * i / (segLen - 1)) }
        val windowPower = window.sumOf { it * it }

        val psdSum = DoubleArray(nHalf)
        var segCount = 0
        var start = 0
        while (start + segLen <= n) {
            val real = DoubleArray(nfft)
            val imag = DoubleArray(nfft)
            for (i in 0 until segLen) real[i] = x[start + i] * window[i]
            SimpleFFT.fft(real, imag)
            for (k in 0 until nHalf) {
                var p = (real[k] * real[k] + imag[k] * imag[k]) / (fsHz * windowPower)
                if (k in 1 until nfft / 2) p *= 2.0
                psdSum[k] += p
            }
            segCount++
            start += hop
        }
        val psd = DoubleArray(nHalf) { psdSum[it] / segCount }
        val freqs = DoubleArray(nHalf) { it * fsHz / nfft }
        return freqs to psd
    }
}
