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
import com.eee312.ppgmonitor.dsp.SampleRateEstimator
import com.eee312.ppgmonitor.dsp.BpmGuard
import com.eee312.ppgmonitor.dsp.ProcessingChain
import com.eee312.ppgmonitor.sensor.HardwareProfile
import com.eee312.ppgmonitor.sensor.PlaybackInput
import com.eee312.ppgmonitor.sensor.table.ColumnMapping
import com.eee312.ppgmonitor.sensor.table.FilePlan
import com.eee312.ppgmonitor.sensor.table.PlanResult
import com.eee312.ppgmonitor.sensor.table.SignalFileAnalyzer
import com.eee312.ppgmonitor.sensor.table.SignalFileSource
import com.eee312.ppgmonitor.sensor.table.TableFormatException
import android.app.AlertDialog
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.withContext
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

    private var liveRateEstimator: SampleRateEstimator? = null
    // v3: DropoutFiller -> PpgPipeline -> BpmGuard (see ProcessingChain).
    @Volatile private var chain: ProcessingChain? = null
    private var liveSampleCount = 0L
    @Volatile private var lastSignalWarnMs = 0L
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
    private var activeFilePlan: FilePlan? = null
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

    // --- File (CSV/Excel) source ------------------------------------------
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareFile(uri)
    }

    /** Opens and inspects the picked file off the main thread, then either
     * starts playback or asks for a column mapping / sample rate. */
    private fun prepareFile(uri: Uri) {
        tvStatus.text = "Reading file..."
        lifecycleScope.launch {
            try {
                val (src, prefix) = withContext(Dispatchers.IO) {
                    val s = SignalFileSource.open(applicationContext, uri)
                    s.openTable().use { t -> s to (t.header to SignalFileAnalyzer.readPrefix(t)) }
                }
                if (prefix.second.isEmpty()) {
                    tvStatus.text = "\"${src.displayName}\" has no data rows."
                    return@launch
                }
                resolvePlan(src, prefix.first, prefix.second, null, null)
            } catch (e: TableFormatException) {
                tvStatus.text = e.message
            } catch (e: Exception) {
                tvStatus.text = "Could not read the file: ${e.message}"
            }
        }
    }

    private fun resolvePlan(
        src: SignalFileSource,
        header: List<String>,
        prefix: List<List<String>>,
        mapping: ColumnMapping?,
        rate: Double?
    ) {
        when (val r = SignalFileAnalyzer.plan(header, prefix, mapping, rate)) {
            is PlanResult.Ready -> startFile(src, r.plan)
            is PlanResult.NeedMapping -> showMappingDialog(r.header, r.suggestion) { m ->
                resolvePlan(src, header, prefix, m, rate)
            }
            is PlanResult.NeedRate -> showRateDialog { hz -> resolvePlan(src, header, prefix, r.mapping, hz) }
        }
    }

    private fun startFile(src: SignalFileSource, plan: FilePlan) {
        val input = PlaybackInput(src.displayName, plan, { src.openTable() })
        startWithSource(
            CsvDatasetSource(input, selectedSpeed(), loop = false),
            SourceType.FILE, null, "File: ${src.displayName}", plan
        )
        if (plan.notes.isNotEmpty()) {
            tvStatus.text = "Playing ${src.displayName} · " + plan.notes.joinToString(" · ")
        }
    }

    /** Asked only when the columns can't be recognised from their names. */
    private fun showMappingDialog(header: List<String>, guess: ColumnMapping, onDone: (ColumnMapping) -> Unit) {
        val options = listOf("(none)") + header
        val roles = listOf(
            "Time" to guess.time, "PPG / IR (required)" to guess.ir.takeIf { it >= 0 }, "Red" to guess.red,
            "Green" to guess.green, "Acc X" to guess.accX, "Acc Y" to guess.accY, "Acc Z" to guess.accZ
        )
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val spinners = roles.map { (label, idx) ->
            container.addView(TextView(this).apply { text = label; textSize = 12f })
            Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(if (idx == null) 0 else idx + 1)
                container.addView(this)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Which column is which?")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Play") { _, _ ->
                fun sel(i: Int) = spinners[i].selectedItemPosition.let { if (it == 0) null else it - 1 }
                val ir = sel(1)
                if (ir == null) {
                    tvStatus.text = "Choose the PPG / IR column to play the file"
                    return@setPositiveButton
                }
                onDone(ColumnMapping(sel(0), ir, sel(2), sel(3), sel(4), sel(5), sel(6)))
            }
            .setNegativeButton("Cancel") { _, _ -> tvStatus.text = "Idle" }
            .show()
    }

    /** Asked only when the file has no usable time column. */
    private fun showRateDialog(onDone: (Double) -> Unit) {
        val input = EditText(this).apply {
            setText(HardwareProfile.NOMINAL_RATE_HZ.toInt().toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("Sample rate (Hz)")
            .setMessage("The file has no time column. At what rate was it recorded?")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val hz = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (hz == null || hz <= 0.0) tvStatus.text = "Enter a sample rate in Hz" else onDone(hz)
            }
            .setNegativeButton("Cancel") { _, _ -> tvStatus.text = "Idle" }
            .show()
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

    /** Dataset: dataset picker + speed + loop. File: speed only (a file is
     * always played exactly once). */
    private fun updateDatasetPanelVisibility() {
        val id = rgSource.checkedRadioButtonId
        datasetOptionsPanel.visibility = if (id == R.id.rbDataset || id == R.id.rbFile) View.VISIBLE else View.GONE
        val datasetOnly = if (id == R.id.rbDataset) View.VISIBLE else View.GONE
        spDataset.visibility = datasetOnly
        cbLoop.visibility = datasetOnly
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
            R.id.rbFile -> pickFileLauncher.launch(FILE_MIME_TYPES)
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
    private fun setUpLiveSpo2Tile(sourceType: SourceType, datasetId: String?, sampleRateHz: Double, filePlan: FilePlan?) {
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
            SourceType.FILE -> when {
                filePlan == null || !filePlan.hasRed -> "N/A (no red channel)"
                !filePlan.spo2Eligible -> "N/A (channels not identified as red / IR)"
                else -> null
            }
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

    private fun startWithSource(
        source: SensorSource,
        sourceType: SourceType,
        datasetId: String?,
        sourceLabel: String? = null,
        filePlan: FilePlan? = null
    ) {
        val isLive = sourceType == SourceType.ESP32_USB || sourceType == SourceType.ESP32_BLE
        liveRateEstimator = if (isLive) SampleRateEstimator() else null
        liveSampleCount = 0L
        chain = newChain(source.sampleRateHz, sourceType, BpmGuard(), 0L)
        tvHr.text = "-- BPM"
        waveformView.clear()
        tvRefHr.text = ""
        tvRefHr.visibility = View.GONE
        lastWaveformUpdateMs = 0L
        lastRefBpmUpdateMs = 0L
        activeSource = source
        activeSourceType = sourceType
        activeDatasetId = datasetId
        activeFilePlan = filePlan
        activeSampleRateHz = source.sampleRateHz
        recorder = SessionRecorder(applicationContext, sourceType, datasetId, source.sampleRateHz, sourceLabel)
        setUpLiveSpo2Tile(sourceType, datasetId, source.sampleRateHz, filePlan)
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
        val fileSpo2AtStop = activeFilePlan?.spo2Eligible ?: false
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
                        datasetIdAtStop, result.sampleRateHz, datasets, fileSpo2AtStop
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
    /** Called from the sensor source's own thread (a worker thread for
     * dataset/file playback, a SerialInputOutputManager read thread for
     * USB, a Binder thread for BLE) - always hop back to the main thread
     * before touching views.
     *
     * v3 chain: source -> DropoutFiller -> PpgPipeline -> BpmGuard. The
     * filler may emit extra slots (samples that never arrived). */
    private fun handleSample(sample: SensorSample) {
        checkLiveRate(sample)
        checkLiveSignalLevel(sample)
        val c = chain ?: return
        for (slot in c.process(sample)) handleSlot(slot)
    }

    private fun handleSlot(slot: ProcessingChain.Slot) {
        val o = slot.out
        val d = o.dsp
        val accMagnitude = sqrt(d.accX.toDouble() * d.accX + d.accY.toDouble() * d.accY + d.accZ.toDouble() * d.accZ)
            .takeIf { it.isFinite() } ?: 0.0
        val p = slot.pipe
        if (p == null) {
            if (o.lostStarted) {
                liveSpo2Tracker?.reset()
                runOnUiThread {
                    tvStatus.text = "Signal lost - check sensor"
                    tvSpo2Live.text = "SpO2 --"
                }
            }
            // LOST: not processed, no BPM/SpO2. The row keeps the time base
            // (its cleanedPpg is meaningless - the LOST flag marks it and
            // History draws a gap).
            recorder?.addSample(d.ir.toDouble(), 0.0, null, Double.NaN, accMagnitude, o.raw, o.flags)
            updateDisplay(slot.tSec, null, Double.NaN)
            return
        }
        if (o.recovered) runOnUiThread { tvStatus.text = "Signal back - restarting heart-rate estimate" }

        // Parallel consumer (Rule Zero): red/green are read straight off
        // the sample here, never through PpgPipeline.Output.
        recorder?.addSample(p.rawPpg, p.cleanedPpg, p.newHrBpm, p.sqi, accMagnitude, o.raw, o.flags)

        liveSpo2Tracker?.let { tracker ->
            tracker.addSample(d.ir, d.red, o.flags)?.let { (ir, red) ->
                updateLiveSpo2Tile(ir, red, activeSampleRateHz, tracker.liveConfig)
            }
        }
        updateDisplay(slot.tSec, p.cleanedPpg.toFloat(), p.sqi)
    }

    private fun updateDisplay(t: Double, cleaned: Float?, sqi: Double) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWaveformUpdateMs < minUiUpdateIntervalMs) return
        lastWaveformUpdateMs = now
        val shown = chain?.guard?.displayAt(t)
        runOnUiThread {
            cleaned?.let { waveformView.addSample(it) }
            tvHr.text = shown?.let { "%.0f BPM".format(it) } ?: "-- BPM"
            if (!sqi.isNaN()) tvSqi.text = "Signal quality: %.0f%%".format(sqi * 100)
        }
    }

    /** Live ESP32 only: measure the real rate from arrival times (or the
     * frame's seq counter) and, once 10 s of data is in, rebuild the DSP at
     * the measured rate if it is more than 1 % off nominal - every filter,
     * window and threshold is built from the rate it is given. Runs on the
     * source's thread, the same thread that calls pipeline.process(). */
    private fun checkLiveRate(sample: SensorSample) {
        val est = liveRateEstimator ?: return
        val t = sample.sourceTimeSec ?: return
        est.addLive(sample.seq ?: liveSampleCount, t)
        liveSampleCount++
        if (liveSampleCount % HardwareProfile.RATE_MIN_SAMPLES != 0L) return
        val measured = est.liveRateHz() ?: return
        if (Math.abs(measured / activeSampleRateHz - 1.0) <= HardwareProfile.RATE_SWITCH_TOLERANCE) return
        // Rebuild the whole chain at the measured rate, keeping the time
        // base continuous and the BPM guard's state.
        val old = chain
        val startSlot = Math.round((old?.slotCount ?: 0L) * measured / activeSampleRateHz)
        activeSampleRateHz = measured
        chain = newChain(measured, activeSourceType, old?.guard ?: BpmGuard(), startSlot)
        if (liveSpo2Tracker != null) liveSpo2Tracker = LiveSpo2Tracker(measured)
        runOnUiThread {
            tvStatus.text = "Sensor runs at %.2f Hz (not %.0f) - processing restarted at the measured rate"
                .format(measured, HardwareProfile.NOMINAL_RATE_HZ)
        }
    }

    /** Live ESP32 only: tell the user when the finger is off the sensor or a
     * channel is on the ADC rail (thresholds in HardwareProfile). */
    private fun checkLiveSignalLevel(sample: SensorSample) {
        if (liveRateEstimator == null) return
        val msg = when {
            sample.ir >= HardwareProfile.SATURATION_COUNTS || (sample.red ?: 0) >= HardwareProfile.SATURATION_COUNTS ->
                "Signal saturated (ADC rail) - lower the LED current"
            sample.ir < HardwareProfile.FINGER_PRESENT_IR -> "No finger detected - place your fingertip on the sensor"
            else -> return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSignalWarnMs < 1000L) return
        lastSignalWarnMs = now
        runOnUiThread { tvStatus.text = msg }
    }

    /** USB frames without seq only have bursty arrival times, which can't
     * tell a missing line from a late one; everything else can. The rig's
     * absolute levels (finger-present, ADC rail) apply to the live ESP32. */
    private fun newChain(rateHz: Double, sourceType: SourceType, guard: BpmGuard, startSlot: Long): ProcessingChain {
        val isLive = sourceType == SourceType.ESP32_USB || sourceType == SourceType.ESP32_BLE
        val filler = ProcessingChain.fillerFor(rateHz, liveUsb = sourceType == SourceType.ESP32_USB, liveHardware = isLive)
        return ProcessingChain(rateHz, filler, guard, startSlot)
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

    companion object {
        /** SAF filter for File: CSV variants and Excel, plus "any type" as a fallback
         * because some file managers label CSVs as octet-stream. */
        private val FILE_MIME_TYPES = arrayOf(
            "text/csv", "text/comma-separated-values", "text/plain", "application/csv",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "*/*"
        )
    }
}
