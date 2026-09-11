package com.eee312.ppgmonitor

import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eee312.ppgmonitor.dsp.PpgPipeline
import com.eee312.ppgmonitor.model.SensorSample
import com.eee312.ppgmonitor.sensor.SensorSource
import com.eee312.ppgmonitor.sensor.SimulatedSensorSource
import com.eee312.ppgmonitor.sensor.UsbSerialSensorSource
import com.eee312.ppgmonitor.ui.WaveformView

/**
 * v1 scope, deliberately: PPG waveform + heart rate only, from either a
 * built-in simulator or a real ESP32 over wired USB serial. See
 * PpgPipeline's header comment for where SpO2 and hemoglobin plug in
 * later - nothing here needs to be restructured to add them, just extended.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvHr: TextView
    private lateinit var tvSqi: TextView
    private lateinit var tvStatus: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var rgSource: RadioGroup
    private lateinit var btnStartStop: Button

    private val pipeline = PpgPipeline()
    private var activeSource: SensorSource? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHr = findViewById(R.id.tvHr)
        tvSqi = findViewById(R.id.tvSqi)
        tvStatus = findViewById(R.id.tvStatus)
        waveformView = findViewById(R.id.waveformView)
        rgSource = findViewById(R.id.rgSource)
        btnStartStop = findViewById(R.id.btnStartStop)

        btnStartStop.setOnClickListener {
            if (running) stopCurrentSource() else onStartClicked()
        }
    }

    private fun onStartClicked() {
        val useUsb = rgSource.checkedRadioButtonId == R.id.rbUsb
        val source: SensorSource =
            if (useUsb) UsbSerialSensorSource(applicationContext) else SimulatedSensorSource()
        startWithSource(source)
    }

    private fun startWithSource(source: SensorSource) {
        pipeline.reset()
        waveformView.clear()
        activeSource = source
        running = true
        btnStartStop.text = "Stop"
        rgSource.isEnabled = false
        for (i in 0 until rgSource.childCount) rgSource.getChildAt(i).isEnabled = false

        source.start(
            onSample = { sample -> handleSample(sample) },
            onStatus = { msg -> runOnUiThread { tvStatus.text = msg } }
        )
    }

    private fun stopCurrentSource() {
        activeSource?.stop()
        activeSource = null
        running = false
        btnStartStop.text = "Start"
        rgSource.isEnabled = true
        for (i in 0 until rgSource.childCount) rgSource.getChildAt(i).isEnabled = true
        tvStatus.text = "Stopped"
    }

    /** Called from the sensor source's own thread (main-looper Handler
     * for the simulator, a SerialInputOutputManager read thread for USB) -
     * always hop back to the main thread before touching views. */
    private fun handleSample(sample: SensorSample) {
        val out = pipeline.process(sample)
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
