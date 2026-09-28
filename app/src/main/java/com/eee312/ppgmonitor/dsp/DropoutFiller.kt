package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.model.SensorSample
import com.eee312.ppgmonitor.sensor.HardwareProfile
import kotlin.math.roundToInt

/** All dropout-filler thresholds in one place (seconds are converted to
 * samples at the stream's rate). */
data class DropoutConfig(
    /** A gap longer than this is "signal lost" instead of being filled. */
    val maxFillSec: Double = 1.0,
    /** Fill value = average of this much of the last good samples. */
    val fillAverageSec: Double = 1.0,
    /** PPG level is judged against the running median of this much good IR. */
    val baselineSec: Double = 2.0,
    /** IR below this fraction of that median = detached. */
    val baselineDropFraction: Double = 0.5,
    /** Samples right after recovery are still bad while the signal settles. */
    val settleSec: Double = 0.2,
    /** A time/seq step longer than this many periods means samples are missing. */
    val gapPeriods: Double = 1.5,
    /** Accelerometer identical on all axes for this long = stuck. */
    val accStuckSec: Double = 1.0,
    /** Never insert more than this much time for one gap (a restart, not a gap). */
    val maxInsertSec: Double = 300.0,
    /** After this long "signal lost", the pre-loss IR level is forgotten so a
     * finger re-seated at a genuinely different level is accepted. Until
     * then the old level is the yardstick, so the detached level itself is
     * never taken for the new normal. */
    val relearnAfterLostSec: Double = 5.0,
    /** Window used for "fraction of filled samples" (the HR window). */
    val hrWindowSec: Double = 12.0
)

/**
 * Sits between every sensor source and the DSP pipeline and deals with
 * short sensor dropouts, one PPG channel at a time (ir, red, green).
 *
 * A slot is BAD when:
 *  - no sample arrived for it (seq jump, or a source-time step longer than
 *    [DropoutConfig.gapPeriods] periods) -> a row is INSERTED for it;
 *  - the PPG level collapsed: IR < 50 % of the running median of the last
 *    2 s of good IR, or (live ESP32 only) below the finger-present level;
 *  - a channel is at the ADC rail (live ESP32 only);
 *  - it is within the first 0.2 s after the signal came back (settling).
 * A run of bad slots up to 1 s long is FILLED: each channel gets the
 * average of its last 1 s of good samples. When a run passes 1 s it becomes
 * "signal lost": from then on nothing is invented, the DSP is restarted by
 * the caller ([Out.lostStarted] / [Out.recovered]), and the whole run is
 * relabelled LOST afterwards (see [relabelLostRuns]).
 *
 * The accelerometer is judged separately: NaN ("nan" from the firmware),
 * all three axes exactly 0, or unchanged for 1 s = ACC_BAD; the last good
 * axes are passed on instead, and the caller freezes LMS adaptation.
 *
 * On a clean stream every sample passes straight through unchanged (the
 * same object), so results on clean data are identical to before.
 */
class DropoutFiller(
    private val fsHz: Double,
    private val cfg: DropoutConfig = DropoutConfig(),
    /** Use source-time steps to find missing samples (false for USB
     * arrival times, which come in bursts; seq numbers are always used). */
    private val gapFromTime: Boolean = true,
    /** Apply the rig's absolute levels (finger-present, ADC rail). */
    private val hardwareLevels: Boolean = false
) {
    data class Out(
        /** What the DSP gets (filled values / last good axes where needed). */
        val dsp: SensorSample,
        /** What actually arrived (null for an inserted slot) - for Export. */
        val raw: SensorSample?,
        val flags: Int,
        /** This slot is where the current gap became "signal lost". */
        val lostStarted: Boolean = false,
        /** First good slot after a "signal lost" gap (restart the DSP). */
        val recovered: Boolean = false
    ) {
        val isLost: Boolean get() = (flags and SampleFlags.LOST) != 0
        val isFilled: Boolean get() = (flags and SampleFlags.FILLED) != 0
    }

    private val period = 1.0 / fsHz
    private val maxFill = (cfg.maxFillSec * fsHz).roundToInt().coerceAtLeast(1)
    private val avgN = (cfg.fillAverageSec * fsHz).roundToInt().coerceAtLeast(1)
    private val baseN = (cfg.baselineSec * fsHz).roundToInt().coerceAtLeast(3)
    private val settleN = (cfg.settleSec * fsHz).roundToInt()
    private val stuckN = (cfg.accStuckSec * fsHz).roundToInt().coerceAtLeast(2)
    private val maxInsert = (cfg.maxInsertSec * fsHz).roundToInt()
    private val winN = (cfg.hrWindowSec * fsHz).roundToInt().coerceAtLeast(1)
    private val relearnN = (cfg.relearnAfterLostSec * fsHz).roundToInt()

    // Good-sample history per channel (ring buffers).
    private val irHist = Ring(maxOf(avgN, baseN))
    private val redHist = Ring(avgN)
    private val greenHist = Ring(avgN)

    private var badRun = 0
    private var lost = false
    private var settleLeft = 0
    private var inGap = false
    private var lastSeq: Long? = null
    private var lastTime: Double? = null
    private var lastGood: SensorSample? = null

    private var lastAcc: FloatArray? = null
    private var sameAccCount = 0
    private var prevAcc: FloatArray? = null

    // Filled/lost share of the last HR window, for BpmGuard.
    private val winFlags = BooleanArray(winN)
    private var winPos = 0
    private var winCount = 0
    private var winBad = 0

    /** Counters for reports. */
    var totalSlots = 0L; private set
    var badSlots = 0L; private set

    fun process(s: SensorSample): List<Out> {
        val outs = ArrayList<Out>(1)
        val missing = missingBefore(s)
        if (missing > 0) {
            val base = lastTime
            for (k in 1..minOf(missing, maxInsert)) {
                val t = base?.let { it + k * period }
                outs += bad(null, t, SampleFlags.INSERTED)
            }
            inGap = true
        }
        s.seq?.let { lastSeq = it }
        s.sourceTimeSec?.let { lastTime = it }

        val levelFlags = levelProblems(s)
        val out = when {
            levelFlags != 0 -> { inGap = true; bad(s, s.sourceTimeSec, levelFlags) }
            inGap || settleLeft > 0 -> {
                if (inGap) { inGap = false; settleLeft = settleN }
                if (settleLeft > 0) {
                    settleLeft--
                    bad(s, s.sourceTimeSec, SampleFlags.SETTLING)
                } else good(s)
            }
            else -> good(s)
        }
        outs += out
        return outs
    }

    private fun missingBefore(s: SensorSample): Int {
        val seq = s.seq
        val ls = lastSeq
        if (seq != null && ls != null) {
            val d = seq - ls
            return if (d > 1 && d <= Int.MAX_VALUE) (d - 1).toInt() else 0
        }
        if (!gapFromTime) return 0
        val t = s.sourceTimeSec ?: return 0
        val lt = lastTime ?: return 0
        val dt = t - lt
        if (dt <= cfg.gapPeriods * period) return 0
        return ((dt / period).roundToInt() - 1).coerceAtLeast(0)
    }

    private fun levelProblems(s: SensorSample): Int {
        var f = 0
        if (hardwareLevels) {
            if (s.ir >= HardwareProfile.SATURATION_COUNTS || (s.red ?: 0) >= HardwareProfile.SATURATION_COUNTS) {
                f = f or SampleFlags.SATURATED
            }
            if (s.ir < HardwareProfile.FINGER_PRESENT_IR) f = f or SampleFlags.LOW_SIGNAL
        }
        if (irHist.size >= minOf(baseN, 10)) {
            val med = irHist.medianOfLast(baseN)
            if (med > 0 && s.ir < cfg.baselineDropFraction * med) f = f or SampleFlags.LOW_SIGNAL
        }
        return f
    }

    private fun good(s: SensorSample): Out {
        val recovered = lost
        lost = false
        badRun = 0
        irHist.add(s.ir.toDouble())
        s.red?.let { redHist.add(it.toDouble()) }
        s.green?.let { greenHist.add(it.toDouble()) }
        lastGood = s
        val (dsp, accFlags) = withAcc(s)
        countSlot(false)
        return Out(dsp, s, accFlags, recovered = recovered)
    }

    private fun bad(raw: SensorSample?, t: Double?, reasons: Int): Out {
        badRun++
        var lostStarted = false
        // Nothing to fill from (stream start, or right after a loss): lost.
        if (!lost && (badRun > maxFill || irHist.size == 0)) {
            lost = true
            lostStarted = true
        }
        if (lost && badRun == maxFill + relearnN) {
            irHist.clear(); redHist.clear(); greenHist.clear() // long loss: re-learn the level
        }
        badSlots++
        val template = raw ?: lastGood ?: SensorSample(0L, 0, null, 0f, 0f, 0f)
        return if (lost) {
            val (dsp, accFlags) = withAcc(template.copy(sourceTimeSec = t ?: template.sourceTimeSec))
            countSlot(true)
            Out(dsp, raw, reasons or accFlags or SampleFlags.LOST, lostStarted = lostStarted)
        } else {
            val filled = template.copy(
                ir = irHist.meanOfLast(avgN).roundToInt(),
                red = if (redHist.size > 0) redHist.meanOfLast(avgN).roundToInt() else template.red,
                green = if (greenHist.size > 0) greenHist.meanOfLast(avgN).roundToInt() else template.green,
                sourceTimeSec = t ?: template.sourceTimeSec,
                seq = null
            )
            val (dsp, accFlags) = withAcc(filled)
            countSlot(true)
            Out(dsp, raw, reasons or accFlags or SampleFlags.FILLED)
        }
    }

    /** Replaces unusable accelerometer axes with the last good ones. */
    private fun withAcc(s: SensorSample): Pair<SensorSample, Int> {
        val ax = s.accX; val ay = s.accY; val az = s.accZ
        val nan = ax.isNaN() || ay.isNaN() || az.isNaN()
        val zero = ax == 0f && ay == 0f && az == 0f
        val p = prevAcc
        if (!nan && p != null && p[0] == ax && p[1] == ay && p[2] == az) sameAccCount++ else sameAccCount = 0
        if (!nan) prevAcc = floatArrayOf(ax, ay, az)
        val stuck = sameAccCount >= stuckN
        if (!nan && !zero && !stuck) {
            lastAcc = floatArrayOf(ax, ay, az)
            return s to 0
        }
        val la = lastAcc
        val fixed = if (la != null) s.copy(accX = la[0], accY = la[1], accZ = la[2])
        else if (nan) s.copy(accX = 0f, accY = 0f, accZ = 0f) else s
        return fixed to SampleFlags.ACC_BAD
    }

    private fun countSlot(bad: Boolean) {
        totalSlots++
        if (winCount == winN) { if (winFlags[winPos]) winBad-- } else winCount++
        winFlags[winPos] = bad
        if (bad) winBad++
        winPos = (winPos + 1) % winN
    }

    /** Share of filled/lost slots in the last HR window. */
    fun windowBadFraction(): Double = if (winCount == 0) 0.0 else winBad.toDouble() / winCount

    /** Call when the DSP restarts after a loss: the next HR window starts clean. */
    fun resetWindow() {
        winFlags.fill(false); winPos = 0; winCount = 0; winBad = 0
    }

    private class Ring(cap: Int) {
        private val buf = DoubleArray(cap)
        private var pos = 0
        var size = 0; private set
        fun add(v: Double) { buf[pos] = v; pos = (pos + 1) % buf.size; if (size < buf.size) size++ }
        fun clear() { size = 0; pos = 0 }
        private fun last(n: Int): DoubleArray {
            val k = minOf(n, size)
            return DoubleArray(k) { buf[((pos - k + it) % buf.size + buf.size) % buf.size] }
        }
        fun meanOfLast(n: Int): Double = last(n).average()
        fun medianOfLast(n: Int): Double {
            val a = last(n); a.sort()
            return if (a.size % 2 == 1) a[a.size / 2] else (a[a.size / 2 - 1] + a[a.size / 2]) / 2
        }
    }

    companion object {
        /**
         * Offline pass over a finished reading's flags: every run of bad
         * slots (FILLED / LOST / INSERTED / SETTLING, touching) that
         * contains a LOST slot becomes LOST as a whole - live, the first
         * second of such a gap was filled before its length was known.
         * Returns the index ranges [start, end) that changed.
         */
        fun relabelLostRuns(flags: IntArray): List<IntRange> {
            val badMask = SampleFlags.FILLED or SampleFlags.LOST
            val changed = ArrayList<IntRange>()
            var i = 0
            while (i < flags.size) {
                if (flags[i] and badMask == 0) { i++; continue }
                var j = i
                var hasLost = false
                while (j < flags.size && flags[j] and badMask != 0) {
                    if (flags[j] and SampleFlags.LOST != 0) hasLost = true
                    j++
                }
                if (hasLost) {
                    var first = -1
                    for (k in i until j) if (flags[k] and SampleFlags.LOST == 0) {
                        flags[k] = (flags[k] and SampleFlags.FILLED.inv()) or SampleFlags.LOST
                        if (first < 0) first = k
                    }
                    if (first >= 0) changed += first until j
                }
                i = j
            }
            return changed
        }
    }
}
