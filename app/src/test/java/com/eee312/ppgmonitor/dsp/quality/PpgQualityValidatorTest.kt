package com.eee312.ppgmonitor.dsp.quality

import com.eee312.ppgmonitor.dsp.HeartRateEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PpgQualityValidatorTest {

    private val fs = 25.0

    /** Runs a real HeartRateEstimator over [signal] to produce the same
     * kind of per-window bpm/sqi series the live app would - exercising
     * the "reuse the app's existing per-window BPM/SQI" contract for
     * real rather than hand-waving plausible-looking numbers. */
    private fun windowSeriesFor(signal: DoubleArray): Pair<List<Double>, List<Double>> {
        val estimator = HeartRateEstimator(fs)
        val bpm = ArrayList<Double>()
        val sqi = ArrayList<Double>()
        for (v in signal) {
            val hr = estimator.addSample(v)
            if (hr != null) {
                bpm += hr
                sqi += estimator.lastSqi
            }
        }
        return bpm to sqi
    }

    @Test
    fun `clean synthetic pulse scores well on cardiac-band and drift checks`() {
        val n = (180 * fs).toInt() // 180s
        val rng = Random(1)
        val ppg = DoubleArray(n) { i ->
            val t = i / fs
            500.0 * sin(2 * PI * 1.2 * t) + rng.nextDouble(-20.0, 20.0) // 72 BPM
        }
        val accMag = DoubleArray(n) { 9.8 + Random(2).nextDouble(-0.02, 0.02) }
        val (bpm, sqi) = windowSeriesFor(ppg)

        val report = PpgQualityValidator.validate(ppg, accMag, fs, bpm, sqi)

        assertTrue("cardiacFraction=${report.cardiacFraction}", report.cardiacFraction > 0.55)
        assertTrue("lowFraction=${report.lowFraction}", report.lowFraction < 0.15)
        assertEquals(QualityVerdict.PASS, report.checks[0].verdict) // cardiac-band dominance
        assertEquals(QualityVerdict.PASS, report.checks[3].verdict) // baseline-drift dominance
        assertEquals(QualityVerdict.PASS, report.checks[4].verdict) // physiological plausibility
        assertTrue(report.medianHrBpm in 60.0..85.0)
    }

    @Test
    fun `pure low-frequency drift fails cardiac-band and drift checks`() {
        val n = (180 * fs).toInt()
        val ppg = DoubleArray(n) { i -> 500.0 * sin(2 * PI * 0.2 * i / fs) } // 12/min - below cardiac band
        val accMag = DoubleArray(n) { 9.8 }
        // No real pulse, so no meaningful HR series - matches a session
        // where HeartRateEstimator never produced a plausible estimate.
        val report = PpgQualityValidator.validate(ppg, accMag, fs, emptyList(), emptyList())

        assertEquals(QualityVerdict.FAIL, report.checks[0].verdict) // cardiac-band dominance
        assertEquals(QualityVerdict.FAIL, report.checks[3].verdict) // baseline-drift dominance
        assertEquals(QualityVerdict.FAIL, report.checks[4].verdict) // no HR series -> FAIL
        assertEquals(QualityOverall.NOT_USABLE, report.overall)
    }

    @Test
    fun `all-zero accelerometer marks pulse-vs-cadence as not applicable`() {
        val n = (60 * fs).toInt()
        val ppg = DoubleArray(n) { i -> 500.0 * sin(2 * PI * 1.2 * i / fs) }
        val accMag = DoubleArray(n) { 0.0 } // e.g. "Hardware - Running 2"
        val (bpm, sqi) = windowSeriesFor(ppg)

        val report = PpgQualityValidator.validate(ppg, accMag, fs, bpm, sqi)

        assertEquals(QualityVerdict.NOT_APPLICABLE, report.checks[5].verdict)
        assertTrue(report.medianCadenceBpm.isNaN())
        assertTrue(report.cadenceSeparationBpm.isNaN())
        // N/A must not be silently counted as a failure.
        assertTrue(report.overall != QualityOverall.NOT_USABLE || report.checks.take(5).any { it.verdict == QualityVerdict.FAIL })
    }
}
