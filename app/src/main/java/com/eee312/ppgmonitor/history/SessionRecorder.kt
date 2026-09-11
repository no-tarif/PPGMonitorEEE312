package com.eee312.ppgmonitor.history

import android.content.Context
import android.util.Log
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
    private val sampleRateHz: Double
) {
    companion object {
        private const val TAG = "SessionRecorder"
        private const val MIN_DURATION_SEC = 15.0
        private const val CHANNEL_CAPACITY = 20_000 // ~800s at 25Hz; comfortably absorbs normal bursts
    }

    data class Result(val readingId: Long, val name: String)

    private data class RawEvent(
        val rawPpg: Double,
        val cleanedPpg: Double,
        val newHrBpm: Double?,
        val sqi: Double,
        val accMagnitude: Double
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

    private val consumerJob: Job = recorderScope.launch {
        for (event in channel) {
            handleEvent(event)
        }
    }

    /** Call from any thread, once per DSP-processed sample. */
    fun addSample(rawPpg: Double, cleanedPpg: Double, newHrBpm: Double?, sqi: Double, accMagnitude: Double) {
        val sent = channel.trySend(RawEvent(rawPpg, cleanedPpg, newHrBpm, sqi, accMagnitude))
        if (sent.isFailure) {
            Log.w(TAG, "Recorder channel full, dropping a sample (sustained rate exceeded write throughput)")
        }
    }

    private suspend fun handleEvent(e: RawEvent) {
        val tSec = totalSamples / sampleRateHz
        totalSamples++

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
                bpm = e.newHrBpm
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
            notes = null
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
        val durationSec = totalSamples / sampleRateHz

        db.readingDao().updateSummary(id, durationSec, avgBpm, finalMinBpm, finalMaxBpm, meanSqi)
        return Result(id, readingName ?: "Reading")
    }
}
