package com.eee312.ppgmonitor.sensor

import com.eee312.ppgmonitor.model.SensorSample

/**
 * Anything that can produce a live stream of [SensorSample]s implements
 * this - [SimulatedSensorSource] (no hardware needed) and
 * [BleSensorSource] (the real ESP32) are interchangeable behind it, so
 * the ViewModel/pipeline never needs to know which one is active.
 *
 * This is also the seam that made it possible to build and test the
 * whole app (BLE permissions aside) before the hardware/BLE contract
 * with your teammate was finalized.
 */
interface SensorSource {
    /** Nominal sample rate this source delivers, Hz. The DSP pipeline
     * is configured to match this - see PpgPipeline.FS_HZ. */
    val sampleRateHz: Double

    fun start(onSample: (SensorSample) -> Unit, onStatus: (String) -> Unit)
    fun stop()
}
