package com.eee312.ppgmonitor.history

import android.content.Context
import com.eee312.ppgmonitor.dataset.DatasetInfo
import com.eee312.ppgmonitor.dsp.quality.PpgQualityValidator
import com.eee312.ppgmonitor.dsp.quality.QualityReport
import com.eee312.ppgmonitor.dsp.spo2.Spo2Config
import com.eee312.ppgmonitor.dsp.spo2.Spo2Engine
import com.eee312.ppgmonitor.dsp.spo2.Spo2Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the batch SpO2 + recording-quality pass once a session has already
 * been saved by [SessionRecorder] - a parallel consumer of the STORED
 * samples, never touching PpgPipeline/HeartRateEstimator or the live BPM
 * path. Marks the reading PENDING immediately so the UI can show
 * "computing..." and fills in the real columns once done.
 *
 * Eligibility rules (Phase 3 spec):
 *   - Live ESP32 (USB/BLE) with a real red channel -> compute.
 *   - Dataset playback -> compute only if the dataset's manifest says
 *     spo2Eligible; otherwise NOT_ELIGIBLE (or SINGLE_WAVELENGTH for a
 *     dataset that explicitly has no red, e.g. "Hardware - Running 2").
 *   - Simulated -> always NOT_ELIGIBLE (SimulatedSensorSource's red is
 *     synthetic, never a real second wavelength - never synthesise SpO2).
 *   - This function is never called for existing (LEGACY) readings
 *     automatically; see HistoryDetailActivity for the on-demand path
 *     when one is opened.
 */
object ReadingPostProcessor {

    suspend fun process(
        context: Context,
        readingId: Long,
        sourceType: SourceType,
        datasetId: String?,
        sampleRateHz: Double,
        datasets: List<DatasetInfo>,
        /** FILE only: true when the file has a column named red (a real
         * second wavelength), false for SNR-picked channels (PhysioNet). */
        fileSpo2Eligible: Boolean = false
    ) = withContext(Dispatchers.Default) {
        val db = AppDatabase.get(context)
        db.readingDao().updateSpo2Status(readingId, Spo2Status.PENDING)

        val samples = db.sampleDao().getForReading(readingId)
        val terminalStatus = terminalEligibility(sourceType, datasetId, datasets, samples, fileSpo2Eligible)

        if (terminalStatus != null) {
            db.readingDao().updateSpo2Status(readingId, terminalStatus)
        } else {
            runSpo2(db, readingId, samples, sampleRateHz, Spo2Config())
        }

        runQuality(db, readingId, samples, sampleRateHz)
    }

    /** Re-runs the SpO2 batch pass for a reading that already has one
     * (status OK or NO_VALID_WINDOWS) with a different [cfg] - used by
     * the "Calibrate against reference oximeter" / "Remove calibration"
     * actions in the detail screen. Never called for a reading whose
     * eligibility hasn't already been established by [process]. */
    suspend fun recomputeSpo2(context: Context, readingId: Long, fsHz: Double, cfg: Spo2Config) =
        withContext(Dispatchers.Default) {
            val db = AppDatabase.get(context)
            val samples = db.sampleDao().getForReading(readingId)
            runSpo2(db, readingId, samples, fsHz, cfg)
        }

    /** Computes and caches the quality report for a reading that doesn't
     * have one yet - the on-demand path for LEGACY readings (recorded
     * before this migration, never touched by [process]). Never
     * recomputes SpO2 for a legacy reading; quality only, per spec. */
    suspend fun computeQualityIfMissing(context: Context, reading: ReadingEntity) {
        if (reading.qualityJson != null) return
        withContext(Dispatchers.Default) {
            val db = AppDatabase.get(context)
            val samples = db.sampleDao().getForReading(reading.id)
            runQuality(db, reading.id, samples, reading.sampleRateHz)
        }
    }

    /** Returns a terminal [Spo2Status] if this session should never even
     * attempt SpO2, or null if [runSpo2] should actually run. */
    private fun terminalEligibility(
        sourceType: SourceType,
        datasetId: String?,
        datasets: List<DatasetInfo>,
        samples: List<SampleEntity>,
        fileSpo2Eligible: Boolean
    ): Spo2Status? = when (sourceType) {
        SourceType.SIMULATED -> Spo2Status.NOT_ELIGIBLE
        SourceType.DATASET -> {
            val info = datasets.find { it.id == datasetId }
            when {
                info == null -> Spo2Status.NOT_ELIGIBLE
                info.spo2Eligible -> null
                !info.hasRed -> Spo2Status.SINGLE_WAVELENGTH
                else -> Spo2Status.NOT_ELIGIBLE
            }
        }
        SourceType.FILE -> when {
            samples.none { it.redRaw != null } -> Spo2Status.SINGLE_WAVELENGTH
            !fileSpo2Eligible -> Spo2Status.NOT_ELIGIBLE
            else -> null
        }
        SourceType.ESP32_USB, SourceType.ESP32_BLE ->
            if (samples.isNotEmpty() && samples.all { it.redRaw == null }) Spo2Status.SINGLE_WAVELENGTH else null
    }

    private suspend fun runSpo2(
        db: AppDatabase,
        readingId: Long,
        samples: List<SampleEntity>,
        fsHz: Double,
        cfg: Spo2Config
    ) {
        val ir = DoubleArray(samples.size) { (samples[it].irRaw ?: 0).toInt().toDouble() }
        val hasRed = samples.isNotEmpty() && samples.none { it.redRaw == null }
        val red = if (hasRed) DoubleArray(samples.size) { samples[it].redRaw!!.toDouble() } else null

        when (val result = Spo2Engine.estimate(ir, red, fsHz, cfg)) {
            is Spo2Result.NotAvailable -> {
                val status = if (red == null) Spo2Status.SINGLE_WAVELENGTH else Spo2Status.TOO_SHORT
                db.readingDao().updateSpo2Status(readingId, status)
            }
            is Spo2Result.Available -> {
                db.spo2WindowDao().deleteForReading(readingId)
                db.spo2WindowDao().insertAll(
                    result.windows.map {
                        Spo2WindowEntity(
                            readingId = readingId,
                            tSec = it.tSec,
                            r = it.r,
                            spo2 = it.spo2,
                            piIr = it.piIr,
                            piRed = it.piRed,
                            valid = it.valid,
                            rejectReason = it.rejectReason
                        )
                    }
                )
                val status = if (result.summary.accepted > 0) Spo2Status.OK else Spo2Status.NO_VALID_WINDOWS
                db.readingDao().updateSpo2Summary(
                    id = readingId,
                    status = status,
                    spo2Median = result.summary.medianAcceptedSpo2,
                    spo2Min = result.summary.minAcceptedSpo2,
                    spo2Max = result.summary.maxAcceptedSpo2,
                    spo2AcceptedPct = result.summary.acceptedPct,
                    rMedian = result.summary.medianR.takeUnless { it.isNaN() },
                    piIrMedian = result.summary.medianPiIr.takeUnless { it.isNaN() },
                    piRedMedian = result.summary.medianPiRed.takeUnless { it.isNaN() },
                    spo2Curve = result.summary.curve.name,
                    // Spo2Summary.calibrationInfo is always a descriptive
                    // string, even when uncalibrated ("none (generic
                    // published curve, uncalibrated)") - store null unless
                    // calibration was actually requested, so the UI's
                    // "if calibrationInfo != null" check means what it says.
                    spo2CalibrationInfo = result.summary.calibrationInfo.takeIf { cfg.singlePoint.enable }
                )
            }
        }
    }

    private suspend fun runQuality(db: AppDatabase, readingId: Long, samples: List<SampleEntity>, fsHz: Double) {
        if (samples.isEmpty()) return
        val cleaned = DoubleArray(samples.size) { samples[it].cleanedPpg }
        val accMag = DoubleArray(samples.size) { samples[it].accMagnitude }
        val bpmSeries = samples.mapNotNull { it.bpm }
        // Paired 1:1 with bpmSeries - both are gated on the same
        // "new HR estimate produced" condition in SessionRecorder.
        val sqiSeries = samples.mapNotNull { it.sqi }

        val report = PpgQualityValidator.validate(cleaned, accMag, fsHz, bpmSeries, sqiSeries)
        db.readingDao().updateQuality(readingId, report.overall.name, qualityReportToJson(report).toString())
    }

    /** org.json.JSONObject.put(String, Double) throws JSONException on
     * NaN/Infinite ("Forbidden numeric value") - and NaN is a normal,
     * expected value here (e.g. cardiacFraction when a segment has zero
     * in-band PSD power, or medianCadenceBpm/cadenceSeparationBpm when
     * check 6 is N/A). Store null instead of crashing. */
    private fun JSONObject.putFinite(key: String, value: Double): JSONObject =
        if (value.isFinite()) put(key, value) else put(key, JSONObject.NULL)

    fun qualityReportToJson(report: QualityReport): JSONObject {
        val checksArray = JSONArray()
        for (c in report.checks) {
            checksArray.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("verdict", c.verdict.name)
                    put("detail", c.detail)
                }
            )
        }
        return JSONObject().apply {
            put("checks", checksArray)
            putFinite("cardiacFraction", report.cardiacFraction)
            putFinite("lowFraction", report.lowFraction)
            putFinite("dominantFreqHz", report.dominantFreqHz)
            putFinite("dominantBpm", report.dominantBpm)
            putFinite("meanSqi", report.meanSqi)
            put("nWindows", report.nWindows)
            putFinite("medianHrBpm", report.medianHrBpm)
            putFinite("iqrHrBpm", report.iqrHrBpm)
            putFinite("medianCadenceBpm", report.medianCadenceBpm)
            putFinite("cadenceSeparationBpm", report.cadenceSeparationBpm)
            put("overall", report.overall.name)
        }
    }
}
