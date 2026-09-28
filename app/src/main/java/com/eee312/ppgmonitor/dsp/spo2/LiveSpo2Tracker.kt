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

    /** One due window. [excluded] = it held filled / lost / settling /
     * saturated samples and must not be used (the tile shows "--"); then
     * the arrays are empty. [acc] is the accelerometer magnitude (for the
     * engine's motion gate), NaN-free or null. */
    data class Snapshot(val ir: DoubleArray, val red: DoubleArray, val acc: DoubleArray?, val excluded: Boolean)

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
    // v3: dropout flags of the samples in the window - a window holding
    // any filled / lost / saturated sample is never used for SpO2.
    private val badBuf = ArrayDeque<Boolean>(windowSamples)
    private val accBuf = ArrayDeque<Double>(windowSamples)
    private var badInWindow = 0
    private var totalSamples = 0L
    private var samplesSinceUpdate = 0

    /** Feed one sample's raw ir/red. Returns a (ir, red) snapshot to run
     * [Spo2Engine.estimate] on when a new provisional estimate is due,
     * else null. [red] null means this source has no second wavelength -
     * the caller should stop calling this and show "N/A - single
     * wavelength" instead (this class doesn't decide that itself, since
     * a single null shouldn't cause a live class to permanently mark a
     * whole session that way - see MainActivity's per-source check). */
    fun addSample(ir: Int, red: Int?, flags: Int = 0, accMag: Double = Double.NaN): Snapshot? {
        totalSamples++
        if (red == null) return null

        val bad = flags and com.eee312.ppgmonitor.dsp.SampleFlags.SPO2_EXCLUDE != 0
        irBuf.addLast(ir.toDouble())
        redBuf.addLast(red.toDouble())
        badBuf.addLast(bad)
        accBuf.addLast(accMag)
        if (bad) badInWindow++
        while (irBuf.size > windowSamples) {
            irBuf.removeFirst()
            redBuf.removeFirst()
            accBuf.removeFirst()
            if (badBuf.removeFirst()) badInWindow--
        }

        samplesSinceUpdate++
        val elapsedSec = totalSamples / fsHz
        if (elapsedSec < warmupPlusWindowSec) return null
        if (irBuf.size < windowSamples) return null
        if (samplesSinceUpdate < stepSamples) return null
        samplesSinceUpdate = 0
        if (badInWindow > 0) return Snapshot(DoubleArray(0), DoubleArray(0), null, excluded = true)
        val acc = if (accBuf.all { it.isFinite() }) accBuf.toDoubleArray() else null
        return Snapshot(irBuf.toDoubleArray(), redBuf.toDoubleArray(), acc, excluded = false)
    }

    /** After a signal loss: drop the window contents (it must refill with
     * clean samples) but don't repeat the session warm-up. */
    fun restart() {
        irBuf.clear()
        redBuf.clear()
        badBuf.clear()
        accBuf.clear()
        badInWindow = 0
        samplesSinceUpdate = 0
    }

    fun reset() {
        irBuf.clear()
        redBuf.clear()
        badBuf.clear()
        accBuf.clear()
        badInWindow = 0
        totalSamples = 0
        samplesSinceUpdate = 0
    }
}
