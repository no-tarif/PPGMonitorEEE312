package com.eee312.ppgmonitor.sensor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.eee312.ppgmonitor.dataset.DatasetInfo
import com.eee312.ppgmonitor.model.SensorSample

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
 * Replays a bundled dataset CSV (assets/datasets/<id>.csv, written by
 * tools/prepare_datasets.py) as a live [SensorSource], sample by sample,
 * paced to the dataset's own sample rate (scaled by [speed]) rather than
 * dumping the whole file into the pipeline at once - from PpgPipeline's
 * point of view this looks exactly like a live sensor.
 *
 * If the dataset has a reference-HR series (assets/datasets/<id>_ref_hr.csv),
 * it's loaded up front (a few hundred rows, trivial) and the current
 * reference BPM is reported via [setOnReferenceBpmListener] as playback
 * passes each timestamp, so the UI can show it next to the live estimate.
 */
class CsvDatasetSource(
    private val context: Context,
    private val dataset: DatasetInfo,
    private val speed: PlaybackSpeed,
    private val loop: Boolean
) : SensorSource {

    override val sampleRateHz: Double = dataset.sampleRateHz

    companion object {
        private const val TAG = "CsvDatasetSource"
        private const val ASSET_DIR = "datasets"
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
                Log.e(TAG, "Dataset playback error", e)
                onStatus("Dataset playback error: ${e.message}")
                running = false
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
        val refSeries = if (dataset.hasReferenceHrSeries) loadReferenceHr() else emptyList()
        val periodMs = if (speed == PlaybackSpeed.FAST) {
            0L
        } else {
            (1000.0 / dataset.sampleRateHz / speed.multiplier).toLong().coerceAtLeast(1)
        }

        onStatus("Playing ${dataset.displayName} at ${speed.label}" + if (loop) " (loop)" else "")

        while (running) {
            var refIdx = 0
            context.assets.open("$ASSET_DIR/${dataset.id}.csv").bufferedReader().use { reader ->
                reader.readLine() // header
                while (running) {
                    val line = reader.readLine() ?: break // natural EOF
                    val parsed = parseLine(line) ?: continue
                    val (t, sample) = parsed

                    while (refIdx < refSeries.size && refSeries[refIdx].first <= t) {
                        onReferenceBpmListener?.invoke(refSeries[refIdx].second)
                        refIdx++
                    }

                    onSample(sample)
                    sleepFor(periodMs)
                }
            }

            if (!running) return // stopped mid-file by the user - not a "finished" event
            if (!loop) break
        }

        if (running) {
            running = false
            onStatus("Finished: ${dataset.displayName}")
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

    /** Parses one "time,ir,red,accX,accY,accZ" line, with two SpO2-era
     * extensions that don't change how the original 8 bundled datasets
     * (always exactly 6 fields, ir/red always numeric) are read:
     *   - an optional 7th trailing "green" field (ignored for now - the
     *     sample model doesn't carry a green channel yet)
     *   - an empty red field (single-wavelength captures, e.g.
     *     "Hardware - Running 2") - parsed as 0, same sentinel meaning as
     *     the bundled green column being absent; DatasetInfo.hasRed tells
     *     callers whether that 0 is real data or a placeholder
     * Returns null (dropping the line) on any other malformed input, same
     * tolerant approach as UsbSerialSensorSource.parseLine(). */
    private fun parseLine(line: String): Pair<Double, SensorSample>? {
        val f = line.split(',')
        if (f.size != 6 && f.size != 7) return null
        return try {
            val t = f[0].trim().toDouble()
            val redField = f[2].trim()
            val sample = SensorSample(
                timestampMs = SystemClock.elapsedRealtime(),
                ir = f[1].trim().toInt(),
                red = if (redField.isEmpty()) 0 else redField.toInt(),
                accX = f[3].trim().toFloat(),
                accY = f[4].trim().toFloat(),
                accZ = f[5].trim().toFloat()
            )
            t to sample
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Skipping malformed dataset line: $line", e)
            null
        }
    }

    private fun loadReferenceHr(): List<Pair<Double, Double>> {
        val path = "$ASSET_DIR/${dataset.id}_ref_hr.csv"
        return try {
            context.assets.open(path).bufferedReader().useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val f = line.split(',')
                    if (f.size != 2) return@mapNotNull null
                    val t = f[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val bpm = f[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                    t to bpm
                }.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No reference HR series for ${dataset.id}", e)
            emptyList()
        }
    }
}
