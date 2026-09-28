package com.eee312.ppgmonitor.history

import android.content.Context
import android.util.Log
import com.eee312.ppgmonitor.dsp.SampleFlags
import com.eee312.ppgmonitor.sensor.HardwareProfile
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
            runSpo2(db, readingId, samples, sampleRateHz, spo2ConfigFor(sourceType))
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
            val reading = db.readingDao().getById(readingId)
            val samples = db.sampleDao().getForReading(readingId)
            // keep the source's own gates; the caller only changes calibration
            val base = reading?.let { spo2ConfigFor(it.sourceType) } ?: Spo2Config()
            runSpo2(db, readingId, samples, fsHz, base.copy(singlePoint = cfg.singlePoint))
        }

    /** SpO2 settings per source: the live ESP32 rig also gets the
     * finger-present and ADC-rail gates (HardwareProfile); recorded data
     * (other units) does not. */
    fun spo2ConfigFor(sourceType: SourceType): Spo2Config =
        if (sourceType == SourceType.ESP32_USB || sourceType == SourceType.ESP32_BLE) {
            Spo2Config(
                fingerPresentDc = HardwareProfile.FINGER_PRESENT_IR.toDouble(),
                saturationCounts = HardwareProfile.SATURATION_COUNTS.toDouble()
            )
        } else Spo2Config()

    /** Computes and caches the quality report for a reading that doesn't
     * have one yet - the on-demand path for LEGACY readings (recorded
     * before this migration, never touched by [process]). Never
     * recomputes SpO2 for a legacy reading; quality only, per spec. */
    suspend fun computeQualityIfMissing(context: Context, reading: ReadingEntity) {
        if (reading.qualityJson != null) return
        withContext(Dispatchers.Default) {
            val db = AppDatabase.get(context)
            // Chunked into plain arrays: a looped 12 h reading has 1.1 M
            // samples, far too many to hold as SampleEntity objects.
            val dao = db.sampleDao()
            val n = dao.countForReading(reading.id)
            if (n == 0) return@withContext
            val cleaned = DoubleArray(n)
            val accMag = DoubleArray(n)
            val bpm = ArrayList<Double>()
            val sqi = ArrayList<Double>()
            var k = 0
            var afterId = 0L
            while (k < n) {
                val chunk = dao.getQualityChunk(reading.id, afterId, 20_000)
                if (chunk.isEmpty()) break
                for (p in chunk) {
                    if (k >= n) break
                    cleaned[k] = p.cleanedPpg; accMag[k] = p.accMagnitude
                    p.bpm?.let { bpm += it }
                    p.sqi?.let { sqi += it }
                    k++
                }
                afterId = chunk.last().id
            }
            val report = PpgQualityValidator.validate(cleaned.copyOf(k), accMag.copyOf(k), reading.sampleRateHz, bpm, sqi)
            db.readingDao().updateQuality(reading.id, report.overall.name, qualityReportToJson(report).toString())
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
        // Raw received values (DC intact). A slot with no received value
        // (inserted gap, or a single missing red cell) carries the previous
        // value for filter continuity and is flagged, so its windows are
        // rejected (DROPOUT) - one missing red sample no longer marks the
        // whole reading "single wavelength".
        val n = samples.size
        val ir = DoubleArray(n)
        val redArr = DoubleArray(n)
        val flags = IntArray(n)
        var lastIr = Double.NaN
        var lastRed = Double.NaN
        for (i in 0 until n) {
            val s = samples[i]
            var f = s.flags ?: 0
            val irV = s.irRaw?.toDouble() ?: if (s.flags == null) s.rawPpg else Double.NaN
            if (irV.isNaN()) f = f or SampleFlags.INSERTED else lastIr = irV
            val redV = s.redRaw?.toDouble() ?: Double.NaN
            if (redV.isNaN()) f = f or SampleFlags.INSERTED else lastRed = redV
            ir[i] = if (irV.isNaN()) lastIr else irV
            redArr[i] = if (redV.isNaN()) lastRed else redV
            flags[i] = f
        }
        // leading gaps (before the first value): take the first value seen
        val firstIr = ir.firstOrNull { !it.isNaN() } ?: 0.0
        val firstRed = redArr.firstOrNull { !it.isNaN() } ?: 0.0
        for (i in 0 until n) { if (ir[i].isNaN()) ir[i] = firstIr; if (redArr[i].isNaN()) redArr[i] = firstRed }
        val hasRed = samples.any { it.redRaw != null }
        val red = if (hasRed) redArr else null
        val accMag = DoubleArray(n) { samples[it].accMagnitude }

        when (val result = Spo2Engine.estimate(ir, red, fsHz, cfg, flags, accMag)) {
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
                            r = it.r.takeIf { v -> v.isFinite() },
                            spo2 = it.spo2.takeIf { v -> v.isFinite() },
                            piIr = it.piIr.takeIf { v -> v.isFinite() },
                            piRed = it.piRed.takeIf { v -> v.isFinite() },
                            valid = it.valid,
                            rejectReason = it.rejectReason
                        )
                    }
                )
                val status = when {
                    result.summary.accepted >= cfg.minValidWindows -> Spo2Status.OK
                    result.summary.accepted > 0 -> Spo2Status.FEW_VALID_WINDOWS
                    else -> Spo2Status.NO_VALID_WINDOWS
                }
                if (result.summary.above100Count > 0) {
                    // Rejected, not hidden: the windows keep their raw value in
                    // spo2_windows and show as hollow points above 100 %.
                    Log.i(
                        "ReadingPostProcessor",
                        "Reading $readingId: ${result.summary.above100Count} window(s) above 100 % " +
                            "(max raw %.1f %%, median R %.3f)".format(result.summary.maxRawSpo2, result.summary.medianR)
                    )
                }
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
