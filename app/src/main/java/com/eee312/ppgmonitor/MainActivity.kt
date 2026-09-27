package com.eee312.ppgmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eee312.ppgmonitor.dataset.DatasetInfo
import com.eee312.ppgmonitor.dataset.DatasetCatalog
import com.eee312.ppgmonitor.dsp.PpgPipeline
import com.eee312.ppgmonitor.dsp.spo2.LiveSpo2Tracker
import com.eee312.ppgmonitor.dsp.spo2.Spo2Config
import com.eee312.ppgmonitor.dsp.spo2.Spo2Engine
import com.eee312.ppgmonitor.dsp.spo2.Spo2Result
import com.eee312.ppgmonitor.history.HistoryActivity
import com.eee312.ppgmonitor.history.ReadingPostProcessor
import com.eee312.ppgmonitor.history.SessionRecorder
import com.eee312.ppgmonitor.history.SourceType
import com.eee312.ppgmonitor.model.SensorSample
import com.eee312.ppgmonitor.sensor.BleSensorSource
import com.eee312.ppgmonitor.sensor.CsvDatasetSource
import com.eee312.ppgmonitor.sensor.PlaybackSpeed
import com.eee312.ppgmonitor.sensor.SensorSource
import com.eee312.ppgmonitor.sensor.UsbSerialSensorSource
import com.eee312.ppgmonitor.ui.ChannelColors
import com.eee312.ppgmonitor.ui.WaveformView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * v1 scope, deliberately: PPG waveform + heart rate only, from a built-in
 * simulator, a bundled dataset replay, or a real ESP32 over wired USB
 * serial. See PpgPipeline's header comment for where SpO2 and hemoglobin
 * plug in later - nothing here needs to be restructured to add them,
 * just extended.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvHr: TextView
    private lateinit var tvRefHr: TextView
    private lateinit var tvSqi: TextView
    private lateinit var tvSpo2Live: TextView
    private lateinit var tvSpo2Detail: TextView
    private lateinit var tvStatus: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var rgSource: RadioGroup
    private lateinit var datasetOptionsPanel: View
    private lateinit var spDataset: Spinner
    private lateinit var rgSpeed: RadioGroup
    private lateinit var cbLoop: CheckBox
    private lateinit var btnStartStop: Button
    private lateinit var btnHistory: Button

    private val pipeline = PpgPipeline()
    private var activeSource: SensorSource? = null
    private var running = false
    private var datasets: List<DatasetInfo> = emptyList()

    // Every session is recorded automatically from Start to Stop (see
    // SessionRecorder's header for why it discards sessions under ~15s
    // and how it batches DB writes) - the same recorder instance is used
    // no matter which SensorSource is active, so Dataset/ESP32
    // all land in the same history through the same path.
    private var recorder: SessionRecorder? = null

    // Provisional live SpO2 tile - NEVER the source of truth for a saved
    // reading (that's always ReadingPostProcessor's whole-record batch
    // pass). Null whenever the active source/dataset isn't SpO2-eligible
    // at all, in which case tvSpo2Detail already shows a static reason
    // and handleSample() doesn't touch these fields.
    private var liveSpo2Tracker: LiveSpo2Tracker? = null

    // Remembered alongside `recorder` purely so finalizeRecording() can
    // hand them to ReadingPostProcessor once the reading is saved -
    // SessionRecorder itself doesn't need to know about SpO2/quality.
    private var activeSourceType: SourceType = SourceType.DATASET
    private var activeDatasetId: String? = null
    private var activeSampleRateHz: Double = PpgPipeline.FS_HZ

    // Caps how often we actually touch views, independent of the active
    // source's sample rate. Every sample still goes through pipeline.process()
    // for DSP correctness (LMS state, HR window) - only the UI post is
    // throttled. Without this, a fast/no-delay dataset replay (especially
    // looped) can enqueue runOnUiThread posts far faster than the UI thread
    // drains them, growing the message queue unboundedly until the app
    // OOMs - this was reproduced during Phase 2 testing (CsvDatasetSource,
    // Fast speed + loop): FATAL EXCEPTION: main, OutOfMemoryError in
    // MainActivity.handleSample via String.format's runOnUiThread lambda.
    private val minUiUpdateIntervalMs = 33L // ~30 fps, plenty for a line chart
    @Volatile private var lastWaveformUpdateMs = 0L
    @Volatile private var lastRefBpmUpdateMs = 0L

    // BLE needs a runtime permission grant before BleSensorSource can be
    // started - see blePermissionsNeeded(). Untested against real
    // firmware (see BleSensorSource.kt's header), unlike the USB path.
    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startWithSource(BleSensorSource(applicationContext), SourceType.ESP32_BLE, null)
        } else {
            tvStatus.text = "Bluetooth permission denied - cannot use ESP32 (BLE) source"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHr = findViewById(R.id.tvHr)
        tvRefHr = findViewById(R.id.tvRefHr)
        tvSqi = findViewById(R.id.tvSqi)
        tvSpo2Live = findViewById<TextView>(R.id.tvSpo2Live).apply { setTextColor(ChannelColors.SPO2) }
        tvSpo2Detail = findViewById(R.id.tvSpo2Detail)
        tvStatus = findViewById(R.id.tvStatus)
        waveformView = findViewById(R.id.waveformView)
        rgSource = findViewById(R.id.rgSource)
        datasetOptionsPanel = findViewById(R.id.datasetOptionsPanel)
        spDataset = findViewById(R.id.spDataset)
        rgSpeed = findViewById(R.id.rgSpeed)
        cbLoop = findViewById(R.id.cbLoop)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnHistory = findViewById(R.id.btnHistory)

        datasets = DatasetCatalog.load(applicationContext)
        spDataset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, datasets.map { it.displayName }
        )

        rgSource.setOnCheckedChangeListener { _, _ -> updateDatasetPanelVisibility() }
        updateDatasetPanelVisibility()

        btnStartStop.setOnClickListener {
            if (running) stopCurrentSource() else onStartClicked()
        }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun updateDatasetPanelVisibility() {
        datasetOptionsPanel.visibility =
            if (rgSource.checkedRadioButtonId == R.id.rbDataset) View.VISIBLE else View.GONE
    }

    private fun onStartClicked() {
        when (rgSource.checkedRadioButtonId) {
            R.id.rbDataset -> {
                if (datasets.isEmpty()) {
                    tvStatus.text = "No bundled datasets found"
                    return
                }
                val info = datasets[spDataset.selectedItemPosition]
                startWithSource(
                    CsvDatasetSource(applicationContext, info, selectedSpeed(), cbLoop.isChecked),
                    SourceType.DATASET,
                    info.id
                )
            }
            R.id.rbUsb -> startWithSource(UsbSerialSensorSource(applicationContext), SourceType.ESP32_USB, null)
            R.id.rbBle -> onBleStartRequested()
        }
    }

    /** BLE needs a permission check/request before it can start, unlike
     * the other sources - everything after permission is granted goes
     * through the same startWithSource() as any other source. */
    private fun onBleStartRequested() {
        val needed = blePermissionsNeeded()
        if (needed.isEmpty()) {
            startWithSource(BleSensorSource(applicationContext), SourceType.ESP32_BLE, null)
        } else {
            tvStatus.text = "Requesting Bluetooth permission..."
            requestBlePermissions.launch(needed)
        }
    }

    private fun blePermissionsNeeded(): Array<String> {
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return candidates.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /** Decides whether this session's source/dataset can plausibly ever
     * produce SpO2, mirroring ReadingPostProcessor.terminalEligibility's
     * rules (kept separate/duplicated rather than shared, since this one
     * only needs a yes/no + message, not the full Spo2Status enum a
     * saved reading uses). Never synthesises SpO2 for a source that
     * doesn't have a real second wavelength. */
    private fun setUpLiveSpo2Tile(sourceType: SourceType, datasetId: String?, sampleRateHz: Double) {
        tvSpo2Live.text = "SpO2 —"
        val ineligibleReason = when (sourceType) {
            SourceType.SIMULATED -> "not computed for this source"
            SourceType.DATASET -> {
                val info = datasets.find { it.id == datasetId }
                when {
                    info == null -> "not computed for this dataset"
                    info.spo2Eligible -> null
                    !info.hasRed -> "N/A — single wavelength"
                    else -> "not computed for this dataset"
                }
            }
            SourceType.ESP32_USB, SourceType.ESP32_BLE -> null
        }
        if (ineligibleReason != null) {
            liveSpo2Tracker = null
            tvSpo2Detail.text = ineligibleReason
        } else {
            liveSpo2Tracker = LiveSpo2Tracker(sampleRateHz)
            tvSpo2Detail.text = ""
        }
    }

    /** Runs off the main thread, per spec - launched from handleSample()
     * only once every ~2s (LiveSpo2Tracker.addSample gates this), never
     * per-sample. */
    private fun updateLiveSpo2Tile(ir: DoubleArray, red: DoubleArray, fsHz: Double, cfg: Spo2Config) {
        CoroutineScope(Dispatchers.Default).launch {
            val result = Spo2Engine.estimate(ir, red, fsHz, cfg)
            val (liveText, detailText) = when (result) {
                is Spo2Result.NotAvailable -> "SpO2 —" to result.reason
                is Spo2Result.Available -> {
                    val w = result.windows.firstOrNull()
                    val live = if (w != null && w.valid) "SpO2 %.0f%%".format(w.spo2) else "SpO2 --"
                    val detail = if (w != null && !w.r.isNaN()) {
                        "R %.3f · PI_IR %.3f%% / PI_RED %.3f%%".format(w.r, 100 * w.piIr, 100 * w.piRed)
                    } else {
                        "low perfusion or non-positive DC this window"
                    }
                    live to detail
                }
            }
            runOnUiThread {
                tvSpo2Live.text = liveText
                tvSpo2Detail.text = detailText
            }
        }
    }

    private fun selectedSpeed(): PlaybackSpeed = when (rgSpeed.checkedRadioButtonId) {
        R.id.rb2x -> PlaybackSpeed.X2
        R.id.rb4x -> PlaybackSpeed.X4
        R.id.rbFast -> PlaybackSpeed.FAST
        else -> PlaybackSpeed.X1
    }

    private fun startWithSource(source: SensorSource, sourceType: SourceType, datasetId: String?) {
        pipeline.reset()
        waveformView.clear()
        tvRefHr.text = ""
        tvRefHr.visibility = View.GONE
        lastWaveformUpdateMs = 0L
        lastRefBpmUpdateMs = 0L
        activeSource = source
        activeSourceType = sourceType
        activeDatasetId = datasetId
        activeSampleRateHz = source.sampleRateHz
        recorder = SessionRecorder(applicationContext, sourceType, datasetId, source.sampleRateHz)
        setUpLiveSpo2Tile(sourceType, datasetId, source.sampleRateHz)
        running = true
        btnStartStop.text = "Stop"
        setControlsEnabled(false)

        source.setOnFinishedListener { onSourceFinishedNaturally() }
        source.setOnReferenceBpmListener { bpm ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastRefBpmUpdateMs < minUiUpdateIntervalMs) return@setOnReferenceBpmListener
            lastRefBpmUpdateMs = now
            runOnUiThread {
                tvRefHr.text = "ref: %.0f BPM".format(bpm)
                tvRefHr.visibility = View.VISIBLE
            }
        }

        source.start(
            onSample = { sample -> handleSample(sample) },
            onStatus = { msg -> runOnUiThread { tvStatus.text = msg } }
        )
    }

    /** User pressed Stop while a source was running. */
    private fun stopCurrentSource() {
        activeSource?.stop()
        activeSource = null
        running = false
        btnStartStop.text = "Start"
        setControlsEnabled(true)
        finalizeRecording("Stopped")
    }

    /** A source (currently only [CsvDatasetSource]) ended on its own -
     * same UI reset as [stopCurrentSource] but leaves tvStatus alone,
     * since the source already posted its own "Finished: ..." message. */
    private fun onSourceFinishedNaturally() {
        runOnUiThread {
            activeSource = null
            running = false
            btnStartStop.text = "Start"
            setControlsEnabled(true)
            finalizeRecording(null)
        }
    }

    /** Ends the current recording (if any) and appends whether/what got
     * saved to tvStatus, per spec: "tell the user on screen why nothing
     * was saved" for sessions too short to keep. [baseStatus] overrides
     * tvStatus's current text (e.g. "Stopped"); pass null to keep
     * whatever the source's own onStatus callback already set. */
    private fun finalizeRecording(baseStatus: String?) {
        val rec = recorder
        recorder = null
        if (rec == null) {
            baseStatus?.let { tvStatus.text = it }
            return
        }
        baseStatus?.let { tvStatus.text = it }
        val sourceTypeAtStop = activeSourceType
        val datasetIdAtStop = activeDatasetId
        val sampleRateHzAtStop = activeSampleRateHz
        lifecycleScope.launch {
            val result = rec.finish()
            if (result != null) {
                // Detached from lifecycleScope on purpose (same reasoning
                // as onDestroy()'s rec.finish() call below): this should
                // keep running even if the activity is destroyed right
                // after Stop is tapped.
                CoroutineScope(Dispatchers.Default).launch {
                    ReadingPostProcessor.process(
                        applicationContext, result.readingId, sourceTypeAtStop,
                        datasetIdAtStop, sampleRateHzAtStop, datasets
                    )
                }
            }
            val base = tvStatus.text.toString()
            tvStatus.text = if (result != null) {
                "$base · saved as ${result.name}"
            } else {
                "$base · too short to save (needs 15s+)"
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        for (i in 0 until rgSource.childCount) rgSource.getChildAt(i).isEnabled = enabled
        for (i in 0 until rgSpeed.childCount) rgSpeed.getChildAt(i).isEnabled = enabled
        spDataset.isEnabled = enabled
        cbLoop.isEnabled = enabled
    }

    /** Called from the sensor source's own thread (main-looper Handler
     * for the simulator, a worker thread for dataset playback, a
     * SerialInputOutputManager read thread for USB) - always hop back to
     * the main thread before touching views. */
    private fun handleSample(sample: SensorSample) {
        val out = pipeline.process(sample)

        val accMagnitude = sqrt(
            sample.accX.toDouble() * sample.accX + sample.accY.toDouble() * sample.accY +
                sample.accZ.toDouble() * sample.accZ
        )
        // Parallel consumer (Rule Zero): red/green are read straight off
        // `sample` here, never through PpgPipeline.Output - the pipeline
        // call above and its result are untouched.
        recorder?.addSample(
            out.rawPpg, out.cleanedPpg, out.newHrBpm, out.sqi, accMagnitude,
            sample.ir, sample.red, sample.green
        )

        liveSpo2Tracker?.let { tracker ->
            tracker.addSample(sample.ir, sample.red)?.let { (ir, red) ->
                updateLiveSpo2Tile(ir, red, activeSampleRateHz, tracker.liveConfig)
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastWaveformUpdateMs < minUiUpdateIntervalMs) return
        lastWaveformUpdateMs = now

        runOnUiThread {
            waveformView.addSample(out.cleanedPpg.toFloat())
            out.latestHrBpm?.let { tvHr.text = "%.0f BPM".format(it) }
            if (!out.sqi.isNaN()) {
                tvSqi.text = "Signal quality: %.0f%%".format(out.sqi * 100)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeSource?.stop()
        // Best-effort: if the activity is destroyed mid-session (no Stop
        // tap), still try to flush/finalize on the recorder's own scope -
        // it isn't tied to lifecycleScope, so this can outlive the
        // activity, but there's no UI left to report back to.
        recorder?.let { rec -> CoroutineScope(Dispatchers.IO).launch { rec.finish() } }
    }
}
