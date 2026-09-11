package com.eee312.ppgmonitor.sensor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eee312.ppgmonitor.model.SensorSample
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a synthetic PPG + accelerometer stream so the whole app -
 * DSP pipeline, waveform view, HR readout - can be built, run, and
 * demoed on an emulator with ZERO hardware. Use this while the BLE
 * contract with your hardware teammate is still being finalized, and
 * keep using it afterwards as a quick regression check ("does the app
 * still show ~72 BPM on synthetic data") whenever you change the DSP.
 *
 * Simulates:
 *   - a ~72 BPM pulsatile component on both ir/red (ir a bit stronger,
 *     matching the real MAX30102 - IR generally penetrates tissue
 *     better than red)
 *   - slow baseline wander (drifting DC), like real skin-contact drift
 *   - a periodic "motion burst" every ~20 s where the accelerometer
 *     shows real movement AND a correlated artifact gets added to the
 *     PPG - this is what your motion-artifact LMS filter is FOR, and
 *     is the easiest way to convince yourself it's doing something:
 *     watch the raw vs. cleaned waveform during a burst.
 *   - sensor + quantization noise
 */
class SimulatedSensorSource(
    override val sampleRateHz: Double = 25.0,
    private val heartRateBpm: Double = 72.0
) : SensorSource {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var startedAtMs = 0L
    private var sampleIndex = 0L

    private var runnable: Runnable? = null

    override fun start(onSample: (SensorSample) -> Unit, onStatus: (String) -> Unit) {
        if (running) return
        running = true
        startedAtMs = SystemClock.elapsedRealtime()
        sampleIndex = 0
        onStatus("Simulated source running at ${sampleRateHz.toInt()} Hz")

        val periodMs = (1000.0 / sampleRateHz).toLong().coerceAtLeast(1)

        runnable = object : Runnable {
            override fun run() {
                if (!running) return
                emitSample(onSample)
                handler.postDelayed(this, periodMs)
            }
        }
        handler.post(runnable!!)
    }

    override fun stop() {
        running = false
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    private fun emitSample(onSample: (SensorSample) -> Unit) {
        val t = sampleIndex / sampleRateHz
        sampleIndex++

        val hrHz = heartRateBpm / 60.0
        val pulse = sin(2 * PI * hrHz * t)
        // A little second-harmonic content, like a real pulse waveform
        // (not a pure sine) - also exercises the harmonic-correction
        // logic in HeartRateEstimator.
        val pulseHarmonic = 0.25 * sin(2 * PI * 2 * hrHz * t)

        val baselineWander = 300.0 * sin(2 * PI * 0.05 * t)

        // Motion burst: ~4 s of walking-like motion every 20 s.
        val cyclePos = t % 20.0
        val inMotion = cyclePos in 3.0..7.0
        val motionHz = 1.6 // ~96 steps/min
        val accelSignal = if (inMotion) 3.0 * sin(2 * PI * motionHz * t) else 0.0
        // The classic wearable-PPG problem: motion couples into the
        // optical signal too. This is exactly what accelerometer-
        // referenced LMS is meant to cancel.
        val motionArtifact = if (inMotion) 800.0 * sin(2 * PI * motionHz * t + 0.4) else 0.0

        val noise = Random.nextDouble(-15.0, 15.0)

        val irDc = 90000.0
        val redDc = 55000.0
        val ir = (irDc + baselineWander + 900.0 * (pulse + pulseHarmonic) + motionArtifact + noise)
            .toInt().coerceIn(0, 262143)
        val red = (redDc + 0.6 * baselineWander + 500.0 * (pulse + pulseHarmonic) + 0.8 * motionArtifact + noise)
            .toInt().coerceIn(0, 262143)

        val accX = (accelSignal + Random.nextDouble(-0.05, 0.05)).toFloat()
        val accY = (0.3 * accelSignal + Random.nextDouble(-0.05, 0.05)).toFloat()
        val accZ = (9.8 + 0.2 * accelSignal + Random.nextDouble(-0.05, 0.05)).toFloat()

        onSample(
            SensorSample(
                timestampMs = SystemClock.elapsedRealtime(),
                ir = ir,
                red = red,
                accX = accX,
                accY = accY,
                accZ = accZ
            )
        )
    }
}
