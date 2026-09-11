package com.eee312.ppgmonitor

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eee312.ppgmonitor.dataset.DatasetInfo
import com.eee312.ppgmonitor.dataset.DatasetCatalog
import com.eee312.ppgmonitor.dsp.PpgPipeline
import com.eee312.ppgmonitor.model.SensorSample
import com.eee312.ppgmonitor.sensor.CsvDatasetSource
import com.eee312.ppgmonitor.sensor.PlaybackSpeed
import com.eee312.ppgmonitor.sensor.SensorSource
import com.eee312.ppgmonitor.sensor.SimulatedSensorSource
import com.eee312.ppgmonitor.sensor.UsbSerialSensorSource
import com.eee312.ppgmonitor.ui.WaveformView

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
    private lateinit var tvStatus: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var rgSource: RadioGroup
    private lateinit var datasetOptionsPanel: View
    private lateinit var spDataset: Spinner
    private lateinit var rgSpeed: RadioGroup
    private lateinit var cbLoop: CheckBox
    private lateinit var btnStartStop: Button

    private val pipeline = PpgPipeline()
    private var activeSource: SensorSource? = null
    private var running = false
    private var datasets: List<DatasetInfo> = emptyList()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHr = findViewById(R.id.tvHr)
        tvRefHr = findViewById(R.id.tvRefHr)
        tvSqi = findViewById(R.id.tvSqi)
        tvStatus = findViewById(R.id.tvStatus)
        waveformView = findViewById(R.id.waveformView)
        rgSource = findViewById(R.id.rgSource)
        datasetOptionsPanel = findViewById(R.id.datasetOptionsPanel)
        spDataset = findViewById(R.id.spDataset)
        rgSpeed = findViewById(R.id.rgSpeed)
        cbLoop = findViewById(R.id.cbLoop)
        btnStartStop = findViewById(R.id.btnStartStop)

        datasets = DatasetCatalog.load(applicationContext)
        spDataset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, datasets.map { it.displayName }
        )

        rgSource.setOnCheckedChangeListener { _, _ -> updateDatasetPanelVisibility() }
        updateDatasetPanelVisibility()

        btnStartStop.setOnClickListener {
            if (running) stopCurrentSource() else onStartClicked()
        }
    }

    private fun updateDatasetPanelVisibility() {
        datasetOptionsPanel.visibility =
            if (rgSource.checkedRadioButtonId == R.id.rbDataset) View.VISIBLE else View.GONE
    }

    private fun onStartClicked() {
        val source: SensorSource = when (rgSource.checkedRadioButtonId) {
            R.id.rbDataset -> {
                if (datasets.isEmpty()) {
                    tvStatus.text = "No bundled datasets found"
                    return
                }
                val info = datasets[spDataset.selectedItemPosition]
                CsvDatasetSource(applicationContext, info, selectedSpeed(), cbLoop.isChecked)
            }
            R.id.rbUsb -> UsbSerialSensorSource(applicationContext)
            else -> SimulatedSensorSource()
        }
        startWithSource(source)
    }

    private fun selectedSpeed(): PlaybackSpeed = when (rgSpeed.checkedRadioButtonId) {
        R.id.rb2x -> PlaybackSpeed.X2
        R.id.rb4x -> PlaybackSpeed.X4
        R.id.rbFast -> PlaybackSpeed.FAST
        else -> PlaybackSpeed.X1
    }

    private fun startWithSource(source: SensorSource) {
        pipeline.reset()
        waveformView.clear()
        tvRefHr.text = ""
        tvRefHr.visibility = View.GONE
        lastWaveformUpdateMs = 0L
        lastRefBpmUpdateMs = 0L
        activeSource = source
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
        tvStatus.text = "Stopped"
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
    }
}
