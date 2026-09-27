package com.eee312.ppgmonitor.history

import android.content.Context
import android.util.Log
import com.eee312.ppgmonitor.dsp.SampleRateEstimator
import com.eee312.ppgmonitor.model.SensorSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Records one session (Start to Stop) into Room, driven by [addSample]
 * calls from MainActivity.handleSample - one call per DSP-processed
 * sample, at the source's full rate, independent of MainActivity's UI
 * render throttle (see MainActivity's minUiUpdateIntervalMs comment).
 *
 * Design notes:
 * - All DB/state work happens on a single dedicated consumer coroutine
 *   (started in [init]), fed through a Channel from [addSample] (which
 *   can be called from any thread - Simulated posts from the main
 *   Handler, CsvDatasetSource from its own worker thread, BLE/USB from
 *   their own callback threads). This avoids needing to synchronize the
 *   batching/aggregation state by hand.
 * - The channel has a bounded capacity, not Channel.UNLIMITED. Phase 2's
 *   UI-flooding OOM (see MainActivity's history) showed that an
 *   unbounded queue fed faster than its consumer can drain it just moves
 *   the same crash somewhere else - a "Fast, no delay" + loop dataset
 *   session could in principle enqueue samples faster than SQLite writes
 *   drain them. If the channel is ever actually full, trySend drops the
 *   sample (logged) rather than growing without bound or blocking the
 *   producer thread. This only matters for that fast/no-delay stress
 *   case; normal-speed sessions never come close to filling it.
 * - No reading row is created until the session has run for
 *   MIN_DURATION_SEC - short aborted sessions never touch the DB at all,
 *   so there's nothing to clean up if they're discarded (see [finish]).
 */
class SessionRecorder(
    context: Context,
    private val sourceType: SourceType,
    private val datasetId: String?,
    private val sampleRateHz: Double,
    private val sourceLabel: String? = null
) {
    companion object {
        private const val TAG = "SessionRecorder"
        private const val MIN_DURATION_SEC = 15.0
        private const val CHANNEL_CAPACITY = 20_000 // ~800s at 25Hz; comfortably absorbs normal bursts
    }

    /** [sampleRateHz] is the rate the stored tSec values and the post-
     * processing use: the measured rate for a live ESP32 source, the
     * file's own rate for Dataset/File. */
    data class Result(val readingId: Long, val name: String, val sampleRateHz: Double)

    private data class RawEvent(
        val rawPpg: Double,
        val cleanedPpg: Double,
        val newHrBpm: Double?,
        val sqi: Double,
        val accMagnitude: Double,
        /** Raw sensor channels, read directly from SensorSample alongside
         * (not through) PpgPipeline.Output - a parallel consumer, per
         * Rule Zero: PpgPipeline itself never gains a red/green output. */
        val irRaw: Int?,
        val redRaw: Int?,
        val greenRaw: Int?,
        val raw: SensorSample?,
        val flags: Int
    )

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val startedAtEpochMs = System.currentTimeMillis()
    private val batchSize = sampleRateHz.roundToInt().coerceAtLeast(1) // ~1s worth per spec

    private val channel = Channel<RawEvent>(CHANNEL_CAPACITY)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var readingId: Long? = null
    private var readingName: String? = null
    private var totalSamples = 0L
    private val pending = ArrayList<SampleEntity>(batchSize)

    private var bpmSum = 0.0
    private var bpmCount = 0
    private var bpmMin = Double.POSITIVE_INFINITY
    private var bpmMax = Double.NEGATIVE_INFINITY
    private var sqiSum = 0.0
    private var sqiCount = 0

    private val isLive = sourceType == SourceType.ESP32_USB || sourceType == SourceType.ESP32_BLE
    private val rateEstimator = SampleRateEstimator()
    private var firstSourceTime = Double.NaN

    private val consumerJob: Job = recorderScope.launch {
        for (event in channel) {
            handleEvent(event)
        }
    }

    /** Call from any thread, once per DSP-processed sample. [raw] is the
     * sample exactly as the source delivered it (null for a slot the
     * dropout filler inserted because nothing arrived) - it is what Export
     * writes; [rawPpg]/[cleanedPpg] are what the DSP saw/produced. */
    fun addSample(
        rawPpg: Double,
        cleanedPpg: Double,
        newHrBpm: Double?,
        sqi: Double,
        accMagnitude: Double,
        raw: SensorSample?,
        flags: Int = 0
    ) {
        val sent = channel.trySend(
            RawEvent(rawPpg, cleanedPpg, newHrBpm, sqi, accMagnitude, raw?.ir, raw?.red, raw?.green, raw, flags)
        )
        if (sent.isFailure) {
            Log.w(TAG, "Recorder channel full, dropping a sample (sustained rate exceeded write throughput)")
        }
    }

    private suspend fun handleEvent(e: RawEvent) {
        val tSec = totalSamples / sampleRateHz
        val slot = totalSamples
        totalSamples++

        val srcT = e.raw?.sourceTimeSec
        if (srcT != null) {
            if (firstSourceTime.isNaN()) firstSourceTime = srcT
            if (isLive) rateEstimator.addLive(e.raw.seq ?: slot, srcT) else rateEstimator.addRecorded(srcT)
        }

        e.newHrBpm?.let { bpm ->
            bpmSum += bpm
            bpmCount++
            if (bpm < bpmMin) bpmMin = bpm
            if (bpm > bpmMax) bpmMax = bpm
        }
        if (!e.sqi.isNaN()) {
            sqiSum += e.sqi
            sqiCount++
        }

        pending.add(
            SampleEntity(
                readingId = 0L, // filled in below once known
                tSec = tSec,
                rawPpg = e.rawPpg,
                cleanedPpg = e.cleanedPpg,
                accMagnitude = e.accMagnitude,
                bpm = e.newHrBpm,
                irRaw = e.irRaw,
                redRaw = e.redRaw,
                greenRaw = e.greenRaw,
                // Same gating as bpm - one sqi value per accepted HR
                // window, not one per raw sample (see SampleEntity's
                // header on why this pairing matters for quality checks).
                sqi = if (e.newHrBpm != null) e.sqi else null,
                tSrc = srcT?.let { it - firstSourceTime },
                accX = e.raw?.accX?.toDouble()?.takeIf { it.isFinite() },
                accY = e.raw?.accY?.toDouble()?.takeIf { it.isFinite() },
                accZ = e.raw?.accZ?.toDouble()?.takeIf { it.isFinite() },
                flags = e.flags
            )
        )

        val id = readingId
        if (id == null) {
            if (totalSamples / sampleRateHz >= MIN_DURATION_SEC) {
                createReading()
            }
        } else if (pending.size >= batchSize) {
            flush(id)
        }
    }

    private suspend fun createReading() {
        val name = ReadingNumberCounter.nextName(appContext)
        val entity = ReadingEntity(
            name = name,
            sourceType = sourceType,
            datasetId = datasetId,
            startedAtEpochMs = startedAtEpochMs,
            durationSec = 0.0,
            sampleRateHz = sampleRateHz,
            avgBpm = null,
            minBpm = null,
            maxBpm = null,
            meanSqi = null,
            notes = null,
            sourceLabel = sourceLabel
        )
        val id = db.readingDao().insert(entity)
        readingId = id
        readingName = name
        flush(id)
    }

    private suspend fun flush(id: Long) {
        if (pending.isEmpty()) return
        db.sampleDao().insertAll(pending.map { it.copy(readingId = id) })
        pending.clear()
    }

    /** Call once, from any coroutine, when the session ends (user Stop or
     * a source finishing on its own). Suspends until every queued sample
     * has been written. Returns null if the session was too short to
     * save - nothing was written to the DB in that case. */
    suspend fun finish(): Result? {
        channel.close()
        consumerJob.join()

        val id = readingId ?: return null
        flush(id)

        val avgBpm = if (bpmCount > 0) bpmSum / bpmCount else null
        val finalMinBpm = if (bpmCount > 0) bpmMin else null
        val finalMaxBpm = if (bpmCount > 0) bpmMax else null
        val meanSqi = if (sqiCount > 0) sqiSum / sqiCount else null

        // Measured rate: live rig -> slope of arrival time vs slot; file ->
        // median time step. A live reading's time base (tSec, duration,
        // post-processing) follows the measured rate; a file keeps its own.
        val measured = if (isLive) rateEstimator.liveRateHz() else rateEstimator.recordedRateHz()
        val effectiveRate = if (isLive && measured != null) measured else sampleRateHz
        if (effectiveRate != sampleRateHz) {
            db.sampleDao().rescaleTime(id, sampleRateHz / effectiveRate)
        }
        val durationSec = totalSamples / effectiveRate

        db.readingDao().updateSummary(id, durationSec, avgBpm, finalMinBpm, finalMaxBpm, meanSqi)
        db.readingDao().updateRates(id, effectiveRate, measured)
        return Result(id, readingName ?: "Reading", effectiveRate)
    }
}
