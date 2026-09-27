package com.eee312.ppgmonitor.sensor

import com.eee312.ppgmonitor.model.SensorSample

/**
 * Anything that can produce a live stream of [SensorSample]s implements
 * this - [CsvDatasetSource] (no hardware needed) and
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

    /** Optional: called once if this source's stream ends on its own
     * (e.g. [CsvDatasetSource] reaching the end of a non-looping dataset)
     * rather than being stopped via [stop]. Live sources (BLE/
     * USB) never end on their own, so the default no-op means they don't
     * need to implement this - MainActivity wires it generically on
     * whichever source is active without needing to know which concrete
     * type it is. */
    fun setOnFinishedListener(listener: () -> Unit) {}

    /** Optional: called whenever this source has a ground-truth/reference
     * value to report alongside its live samples - currently only
     * [CsvDatasetSource], from a bundled dataset's reference-HR series
     * (see tools/prepare_datasets.py). Default no-op means other sources
     * don't need to implement this. */
    fun setOnReferenceBpmListener(listener: (Double) -> Unit) {}
}
