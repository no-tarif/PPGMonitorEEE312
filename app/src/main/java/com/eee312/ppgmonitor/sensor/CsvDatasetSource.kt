package com.eee312.ppgmonitor.sensor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.eee312.ppgmonitor.dataset.DatasetInfo
import com.eee312.ppgmonitor.model.SensorSample
import com.eee312.ppgmonitor.sensor.table.ColumnDetector
import com.eee312.ppgmonitor.sensor.table.CsvTableReader
import com.eee312.ppgmonitor.sensor.table.FilePlan
import com.eee312.ppgmonitor.sensor.table.PlaybackRowStream
import com.eee312.ppgmonitor.sensor.table.TableReader

/** How fast to replay a dataset relative to its real recorded rate.
 * FAST has no inter-sample delay at all - useful for quickly scrubbing
 * through an 8-minute record while testing, not for judging live UX. */
enum class PlaybackSpeed(val multiplier: Double, val label: String) {
    X1(1.0, "1x"),
    X2(2.0, "2x"),
    X4(4.0, "4x"),
    FAST(Double.POSITIVE_INFINITY, "Fast");
}

/**
 * Something that can be replayed as a sensor stream: a bundled dataset
 * asset or a CSV/Excel file the user picked. [openTable] must return a
 * fresh reader positioned at the first data row each time (looping reopens
 * it); [plan] says which columns are what and at what rate.
 */
class PlaybackInput(
    val displayName: String,
    val plan: FilePlan,
    val openTable: () -> TableReader,
    /** Optional (time, bpm) ground truth, e.g. a dataset's ECG R-R series. */
    val referenceHr: () -> List<Pair<Double, Double>> = { emptyList() }
) {
    companion object {
        private const val ASSET_DIR = "datasets"

        /** A bundled dataset (assets/datasets/<id>.csv, written by
         * tools/prepare_datasets.py: time,ir,red,accX,accY,accZ[,green]). */
        fun forDataset(context: Context, dataset: DatasetInfo): PlaybackInput {
            val open = { CsvTableReader(context.assets.open("$ASSET_DIR/${dataset.id}.csv").bufferedReader()) }
            val header = open().use { it.header }
            val mapping = ColumnDetector.detect(header).mapping
                ?: error("Dataset ${dataset.id} has no ir column")
            val plan = FilePlan(header, mapping, dataset.sampleRateHz, emptyList(), dataset.sampleRateHz)
            val ref = {
                if (!dataset.hasReferenceHrSeries) emptyList() else try {
                    context.assets.open("$ASSET_DIR/${dataset.id}_ref_hr.csv").bufferedReader().useLines { lines ->
                        lines.drop(1).mapNotNull { line ->
                            val f = line.split(',')
                            if (f.size != 2) return@mapNotNull null
                            val t = f[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                            val bpm = f[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                            t to bpm
                        }.toList()
                    }
                } catch (e: Exception) {
                    Log.w("CsvDatasetSource", "No reference HR series for ${dataset.id}", e)
                    emptyList()
                }
            }
            return PlaybackInput(dataset.displayName, plan, open, ref)
        }
    }
}

/**
 * Replays a [PlaybackInput] (bundled dataset or uploaded CSV/Excel file) as
 * a live [SensorSource], sample by sample, paced to the input's own sample
 * rate (scaled by [speed]) rather than dumping the whole file into the
 * pipeline at once - from PpgPipeline's point of view this looks exactly
 * like a live sensor.
 *
 * Rows are streamed (PlaybackRowStream over a TableReader); a row whose PPG
 * cell is blank/NaN is not emitted, which leaves a gap in the source time
 * that the dropout filler then handles like a missing sensor sample.
 *
 * If the input has a reference-HR series, the current reference BPM is
 * reported via [setOnReferenceBpmListener] as playback passes each time.
 */
class CsvDatasetSource(
    private val input: PlaybackInput,
    private val speed: PlaybackSpeed,
    private val loop: Boolean
) : SensorSource {

    /** Convenience for the bundled datasets. */
    constructor(context: Context, dataset: DatasetInfo, speed: PlaybackSpeed, loop: Boolean) :
        this(PlaybackInput.forDataset(context, dataset), speed, loop)

    override val sampleRateHz: Double = input.plan.playbackRateHz

    companion object {
        private const val TAG = "CsvDatasetSource"
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var onFinishedListener: (() -> Unit)? = null
    private var onReferenceBpmListener: ((Double) -> Unit)? = null

    override fun setOnFinishedListener(listener: () -> Unit) {
        onFinishedListener = listener
    }

    override fun setOnReferenceBpmListener(listener: (Double) -> Unit) {
        onReferenceBpmListener = listener
    }

    override fun start(onSample: (SensorSample) -> Unit, onStatus: (String) -> Unit) {
        if (running) return
        running = true

        worker = Thread {
            try {
                playLoop(onSample, onStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                onStatus("Playback error: ${e.message}")
                val wasRunning = running
                running = false
                // End the session cleanly (whatever was played is saved).
                if (wasRunning) onFinishedListener?.invoke()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun playLoop(onSample: (SensorSample) -> Unit, onStatus: (String) -> Unit) {
        val refSeries = input.referenceHr()
        val periodMs = if (speed == PlaybackSpeed.FAST) {
            0L
        } else {
            (1000.0 / sampleRateHz / speed.multiplier).toLong().coerceAtLeast(1)
        }

        onStatus("Playing ${input.displayName} at ${speed.label}" + if (loop) " (loop)" else "")

        // Source time keeps increasing across loop passes, so a looped
        // reading's exported time column stays monotonic.
        var loopOffsetSec = 0.0
        var lastT = 0.0
        while (running) {
            var refIdx = 0
            val passOffset = loopOffsetSec
            PlaybackRowStream(input.openTable(), input.plan).use { rows ->
                while (running && rows.hasNext()) {
                    val row = rows.next()
                    val t = row.t
                    lastT = t
                    val ir = PlaybackRowStream.counts(row.ir) ?: continue // missing PPG: leave a gap

                    while (refIdx < refSeries.size && refSeries[refIdx].first <= t) {
                        onReferenceBpmListener?.invoke(refSeries[refIdx].second)
                        refIdx++
                    }

                    onSample(
                        SensorSample(
                            timestampMs = SystemClock.elapsedRealtime(),
                            ir = ir,
                            red = PlaybackRowStream.counts(row.red),
                            accX = row.accX,
                            accY = row.accY,
                            accZ = row.accZ,
                            green = PlaybackRowStream.counts(row.green),
                            sourceTimeSec = passOffset + t
                        )
                    )
                    sleepFor(periodMs)
                }
            }

            if (!running) return // stopped mid-file by the user - not a "finished" event
            if (!loop) break
            loopOffsetSec = passOffset + lastT + 1.0 / sampleRateHz
        }

        if (running) {
            running = false
            onStatus("Finished: ${input.displayName}")
            onFinishedListener?.invoke()
        }
    }

    private fun sleepFor(periodMs: Long) {
        if (periodMs <= 0) return
        try {
            Thread.sleep(periodMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // loop re-checks `running` and exits cleanly
        }
    }
}
