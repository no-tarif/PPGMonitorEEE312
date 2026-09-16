package com.eee312.ppgmonitor.history

import com.eee312.ppgmonitor.dsp.quality.QualityCheck
import com.eee312.ppgmonitor.dsp.quality.QualityOverall
import com.eee312.ppgmonitor.dsp.quality.QualityReport
import com.eee312.ppgmonitor.dsp.quality.QualityVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a real crash found on-device: opening a LEGACY
 * reading whose quality report contains a NaN field (e.g. cadence
 * separation when check 6 is N/A) threw
 * org.json.JSONException: Forbidden numeric value: NaN from
 * JSONObject.put(String, Double) - org.json rejects NaN/Infinite outright.
 * Uses the REAL org.json (testImplementation(libs.orgJson)), not the
 * Android SDK's unit-test stub, which would not have caught this: the
 * stub throws on nearly every call regardless of arguments, so it can't
 * distinguish "crashes on NaN" from "crashes on everything".
 */
class ReadingPostProcessorTest {

    private fun reportWith(
        cardiacFraction: Double = 0.7,
        medianCadenceBpm: Double = Double.NaN,
        cadenceSeparationBpm: Double = Double.NaN
    ) = QualityReport(
        checks = listOf(QualityCheck("Cardiac-band dominance", QualityVerdict.PASS, "70%")),
        cardiacFraction = cardiacFraction,
        lowFraction = 0.1,
        dominantFreqHz = 1.2,
        dominantBpm = 72.0,
        meanSqi = 0.6,
        nWindows = 10,
        medianHrBpm = 72.0,
        iqrHrBpm = 5.0,
        medianCadenceBpm = medianCadenceBpm,
        cadenceSeparationBpm = cadenceSeparationBpm,
        overall = QualityOverall.GOOD
    )

    @Test
    fun `NaN fields do not throw and are stored as JSON null`() {
        val json = ReadingPostProcessor.qualityReportToJson(reportWith())
        assertTrue(json.isNull("medianCadenceBpm"))
        assertTrue(json.isNull("cadenceSeparationBpm"))
        assertEquals(0.7, json.getDouble("cardiacFraction"), 1e-9)
    }

    @Test
    fun `zero in-band power (all fields NaN) does not throw`() {
        val json = ReadingPostProcessor.qualityReportToJson(
            reportWith(cardiacFraction = Double.NaN, medianCadenceBpm = Double.NaN, cadenceSeparationBpm = Double.NaN)
        )
        assertTrue(json.isNull("cardiacFraction"))
    }

    @Test
    fun `finite values round-trip normally`() {
        val json = ReadingPostProcessor.qualityReportToJson(reportWith(medianCadenceBpm = 80.0, cadenceSeparationBpm = 8.0))
        assertEquals(80.0, json.getDouble("medianCadenceBpm"), 1e-9)
        assertEquals(8.0, json.getDouble("cadenceSeparationBpm"), 1e-9)
    }
}
