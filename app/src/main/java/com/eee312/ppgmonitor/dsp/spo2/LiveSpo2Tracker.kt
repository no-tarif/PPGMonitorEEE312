package com.eee312.ppgmonitor.dsp.spo2

import java.util.ArrayDeque

/**
 * Rolling-buffer live SpO2 estimate for the main screen's provisional
 * tile - NOT the source of truth for a saved reading's SpO2 (that's
 * always the whole-record batch pass in ReadingPostProcessor, run once
 * the session ends). Buffers the last windowSec seconds of raw ir/red
 * and, once warmupSec+windowSec have elapsed in the session, hands back
 * a snapshot every stepSec seconds for the caller to run
 * [Spo2Engine.estimate] on - OFF THE CALLING THREAD, since this is
 * called from whatever thread is feeding samples (main-looper Handler
 * for Simulated, a worker thread for dataset/USB).
 */
class LiveSpo2Tracker(private val fsHz: Double, private val cfg: Spo2Config = Spo2Config()) {
    private val windowSamples = Math.round(cfg.windowSec * fsHz).toInt().coerceAtLeast(2)
    private val stepSamples = Math.round(cfg.stepSec * fsHz).toInt().coerceAtLeast(1)

    /** Config to run on the returned per-window snapshot: no warm-up (the
     * warm-up skip already happened by only starting to return snapshots
     * after [warmupPlusWindowSec] of session time), same curve/thresholds/
     * calibration as the caller's [cfg] otherwise. */
    val liveConfig: Spo2Config = cfg.copy(warmupSec = 0.0)

    val warmupPlusWindowSec: Double get() = cfg.warmupSec + cfg.windowSec

    private val irBuf = ArrayDeque<Double>(windowSamples)
    private val redBuf = ArrayDeque<Double>(windowSamples)
    private var totalSamples = 0L
    private var samplesSinceUpdate = 0

    /** Feed one sample's raw ir/red. Returns a (ir, red) snapshot to run
     * [Spo2Engine.estimate] on when a new provisional estimate is due,
     * else null. [red] null means this source has no second wavelength -
     * the caller should stop calling this and show "N/A - single
     * wavelength" instead (this class doesn't decide that itself, since
     * a single null shouldn't cause a live class to permanently mark a
     * whole session that way - see MainActivity's per-source check). */
    fun addSample(ir: Int, red: Int?): Pair<DoubleArray, DoubleArray>? {
        totalSamples++
        if (red == null) return null

        irBuf.addLast(ir.toDouble())
        redBuf.addLast(red.toDouble())
        while (irBuf.size > windowSamples) {
            irBuf.removeFirst()
            redBuf.removeFirst()
        }

        samplesSinceUpdate++
        val elapsedSec = totalSamples / fsHz
        if (elapsedSec < warmupPlusWindowSec) return null
        if (irBuf.size < windowSamples) return null
        if (samplesSinceUpdate < stepSamples) return null
        samplesSinceUpdate = 0

        return irBuf.toDoubleArray() to redBuf.toDoubleArray()
    }

    fun reset() {
        irBuf.clear()
        redBuf.clear()
        totalSamples = 0
        samplesSinceUpdate = 0
    }
}
