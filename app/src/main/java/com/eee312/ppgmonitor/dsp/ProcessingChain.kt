package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.model.SensorSample

/**
 * The per-sample processing the app runs for every source (v3):
 *
 *   source sample -> DropoutFiller -> PpgPipeline (unchanged) -> BpmGuard
 *
 * Kept out of MainActivity so the JVM tests (Phase 6 evidence, golden
 * comparison) run exactly the same code as the phone.
 *
 * - LOST slots are not processed (no BPM/SpO2 from them); when the signal
 *   comes back the pipeline is reset, so it restarts cleanly.
 * - LMS adaptation is frozen on FILLED and ACC_BAD slots.
 * - Estimates go to the guard with the filled share of their window.
 */
class ProcessingChain(
    val fsHz: Double,
    val filler: DropoutFiller,
    val guard: BpmGuard = BpmGuard(),
    /** Slot numbering continues from here (e.g. after a rate switch). */
    startSlot: Long = 0L
) {
    data class Slot(
        val out: DropoutFiller.Out,
        /** Slot time, seconds from the session start. */
        val tSec: Double,
        /** Null for LOST slots (not processed). */
        val pipe: PpgPipeline.Output?
    )

    val pipeline = PpgPipeline(fsHz)
    var slotCount = startSlot
        private set

    fun process(sample: SensorSample): List<Slot> {
        val outs = filler.process(sample)
        val result = ArrayList<Slot>(outs.size)
        for (o in outs) {
            val t = slotCount / fsHz
            slotCount++
            if (o.isLost) {
                if (o.lostStarted) guard.signalLost(t)
                result += Slot(o, t, null)
                continue
            }
            if (o.recovered) {
                pipeline.reset()
                filler.resetWindow()
            }
            val adapt = o.flags and (SampleFlags.FILLED or SampleFlags.ACC_BAD) == 0
            val p = pipeline.process(o.dsp, adapt)
            p.newHrBpm?.let { guard.onEstimate(t, it, filler.windowBadFraction(), false) }
            result += Slot(o, t, p)
        }
        return result
    }

    companion object {
        /** The filler settings the app uses for a source. */
        fun fillerFor(fsHz: Double, liveUsb: Boolean, liveHardware: Boolean) =
            DropoutFiller(fsHz, gapFromTime = !liveUsb, hardwareLevels = liveHardware)
    }
}
