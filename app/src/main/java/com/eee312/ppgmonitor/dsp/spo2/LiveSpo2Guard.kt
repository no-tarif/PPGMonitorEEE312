package com.eee312.ppgmonitor.dsp.spo2

import kotlin.math.abs

/**
 * What the live SpO2 tile shows, window by window (the saved reading's
 * SpO2 is always the whole-record batch pass, not this):
 *  - shown value = mean of the last [smoothCount] accepted windows;
 *  - a window more than [maxJump] points away from the shown value is
 *    held until the next window confirms it (within [maxJump] of it), the
 *    same idea as the BPM guard; a one-off jump is dropped;
 *  - [maxInvalidInARow] invalid windows in a row, or any window with
 *    dropout / finger off / signal lost ([clear]), -> "--", never a stale
 *    value.
 */
class LiveSpo2Guard(
    private val maxJump: Double = 3.0,
    private val smoothCount: Int = 3,
    private val maxInvalidInARow: Int = 2
) {
    private val accepted = ArrayDeque<Double>()
    private var pending: Double? = null
    private var invalidRun = 0

    /** Null = show "--". */
    var shown: Double? = null
        private set

    fun onValid(spo2: Double): Double? {
        invalidRun = 0
        val cur = shown
        if (cur == null) {
            // Nothing trusted yet: two windows that agree start the display.
            val p = pending
            pending = spo2
            if (p != null && abs(spo2 - p) <= maxJump) { accept(p); accept(spo2); pending = null }
            return shown
        }
        val p = pending
        when {
            abs(spo2 - cur) <= maxJump -> { pending = null; accept(spo2) }
            p != null && abs(spo2 - p) <= maxJump -> {
                // confirmed change: restart the smoothing from the new level
                accepted.clear(); pending = null; accept(p); accept(spo2)
            }
            else -> pending = spo2
        }
        return shown
    }

    fun onInvalid(): Double? {
        invalidRun++
        if (invalidRun >= maxInvalidInARow) clear()
        return shown
    }

    fun clear() {
        accepted.clear(); pending = null; shown = null
    }

    private fun accept(v: Double) {
        accepted.addLast(v)
        while (accepted.size > smoothCount) accepted.removeFirst()
        shown = accepted.average().coerceAtMost(100.0) // never shown above 100 %
    }
}
