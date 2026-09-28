package com.eee312.ppgmonitor.dsp

import kotlin.math.abs
import kotlin.math.sign

/** Every BPM-guard number in one place. */
data class BpmGuardConfig(
    val minBpm: Double = 40.0,
    val maxBpm: Double = 210.0,
    /** Estimates from a window with more filled samples than this are ignored. */
    val maxFilledFraction: Double = 0.25,
    /** Further than this from the trend -> held as "pending". */
    val spikeBpm: Double = 12.0,
    /** "Agrees with" distance (trend back / pending confirmed / start-up pair). */
    val confirmBpm: Double = 6.0,
    /** A pending value nobody confirmed is dropped after this long. */
    val pendingExpirySec: Double = 5.0,
    /** Shown / saved BPM moves at most this fast toward the trend. */
    val maxSlewBpmPerSec: Double = 3.0,
    /** During signal loss the last value stays this long, then "--". */
    val lossHoldSec: Double = 3.0,
    /** Trend = median of this many last accepted values. */
    val trendCount: Int = 3,
    /** This many valid estimates in a row that all disagree with the trend
     * (> spikeBpm) mean the trend itself is stale: re-acquire (the next two
     * agreeing estimates set the new level). Stops the guard locking onto
     * a wrong level. */
    val relockAfter: Int = 3
)

/**
 * The stage between HeartRateEstimator and everything that shows or keeps
 * BPM (live number, saved series, History stats and graph). No change to
 * the estimator itself - this only decides which estimates to believe and
 * how fast the shown number may move.
 *
 *  - Invalid estimates (outside 40-210, window > 25 % filled, or window with
 *    signal loss) are ignored: the previous value is held.
 *  - Start / after signal loss: the first estimate is only believed when the
 *    next one agrees within 6 BPM (no ramp from a stale value).
 *  - Spike check: > 12 BPM from the trend (median of the last 3 accepted)
 *    -> pending. Next estimate back within 6 of the trend -> the pending one
 *    was a spike and is recorded as the average of its neighbours
 *    (80, 100, 82 -> 80, 81, 82). Next estimate within 6 of the pending one
 *    -> a real change: both accepted. Pending expires after 5 s.
 *  - The shown / saved value follows the trend at <= 3 BPM/s.
 *
 * [onEstimate] is used live and offline alike. It returns the records
 * that became final (the value to keep for each estimate slot, time-
 * ordered, before slew limiting); [Offline.run] turns a whole series into
 * the slew-limited series History shows.
 */
class BpmGuard(private val cfg: BpmGuardConfig = BpmGuardConfig()) {

    data class Record(val tSec: Double, val bpm: Double)

    private val accepted = ArrayDeque<Double>()
    private var candidate: Record? = null       // start-up / after loss: waiting for agreement
    private var pending: Record? = null         // possible spike
    private var lastRecord: Record? = null
    private var acquiring = true
    private var disagreeRun = 0

    // Display (live) state.
    private var shown = Double.NaN
    private var shownAt = Double.NaN
    private var lossSince = Double.NaN

    val trend: Double?
        get() = if (accepted.isEmpty()) null else accepted.sorted().let { it[it.size / 2] }

    /** One estimate slot. [bpm] null = the estimator produced nothing
     * usable. Returns finalised records. */
    fun onEstimate(tSec: Double, bpm: Double?, filledFraction: Double = 0.0, windowHasLoss: Boolean = false): List<Record> {
        val out = ArrayList<Record>(2)
        expirePending(tSec, out)
        val valid = bpm != null && bpm in cfg.minBpm..cfg.maxBpm &&
            filledFraction <= cfg.maxFilledFraction && !windowHasLoss
        if (!valid) return out
        val e = Record(tSec, bpm!!)
        lossSince = Double.NaN

        if (acquiring) {
            val c = candidate
            if (c != null && abs(e.bpm - c.bpm) <= cfg.confirmBpm) {
                acquiring = false
                candidate = null
                accept(c, out)
                accept(e, out)
                // no ramp from a stale value: jump straight to the new level
                shown = e.bpm; shownAt = e.tSec
            } else {
                candidate = e
            }
            return out
        }

        val tr = trend!!
        if (abs(e.bpm - tr) > cfg.spikeBpm) disagreeRun++ else disagreeRun = 0
        if (disagreeRun >= cfg.relockAfter) {
            // The trend no longer describes the signal: start over from here.
            pending?.let { record(Record(it.tSec, lastRecord?.bpm ?: tr), out) }
            pending = null
            accepted.clear()
            acquiring = true
            disagreeRun = 0
            candidate = e
            return out
        }
        val p = pending
        if (p == null) {
            if (abs(e.bpm - tr) <= cfg.spikeBpm) accept(e, out) else pending = e
            return out
        }
        when {
            abs(e.bpm - tr) <= cfg.confirmBpm -> {
                // pending was a one-off spike: its slot gets the neighbours' average
                val before = lastRecord?.bpm ?: tr
                pending = null
                record(Record(p.tSec, (before + e.bpm) / 2), out)
                accept(e, out)
            }
            abs(e.bpm - p.bpm) <= cfg.confirmBpm -> {
                pending = null
                accept(p, out)
                accept(e, out)
            }
            else -> {
                // neither: the old pending is dropped (slot holds the trend), the new one waits
                record(Record(p.tSec, lastRecord?.bpm ?: tr), out)
                pending = e
            }
        }
        return out
    }

    private fun expirePending(tSec: Double, out: MutableList<Record>) {
        val p = pending ?: return
        if (tSec - p.tSec > cfg.pendingExpirySec) {
            pending = null
            record(Record(p.tSec, lastRecord?.bpm ?: trend ?: p.bpm), out)
        }
    }

    private fun accept(r: Record, out: MutableList<Record>) {
        accepted.addLast(r.bpm)
        while (accepted.size > cfg.trendCount) accepted.removeFirst()
        record(r, out)
    }

    private fun record(r: Record, out: MutableList<Record>) {
        lastRecord = r
        out += r
    }

    /** Signal lost at [tSec]: start over (first estimate needs agreement). */
    fun signalLost(tSec: Double) {
        if (lossSince.isNaN()) lossSince = tSec
        acquiring = true
        disagreeRun = 0
        candidate = null
        pending = null
        accepted.clear()
    }

    /** Live display value at [tSec]: moves toward the trend at <= 3 BPM/s;
     * held for 3 s after a loss, then null ("--"). */
    fun displayAt(tSec: Double): Double? {
        if (!lossSince.isNaN()) {
            return if (tSec - lossSince <= cfg.lossHoldSec && shown.isFinite()) shown else null
        }
        val target = (if (acquiring) null else trend) ?: return null
        if (!shown.isFinite()) { shown = target; shownAt = tSec; return shown }
        val dt = (tSec - shownAt).coerceAtLeast(0.0)
        val step = cfg.maxSlewBpmPerSec * dt
        val diff = target - shown
        shown = if (abs(diff) <= step) target else shown + sign(diff) * step
        shownAt = tSec
        return shown
    }

    /** End of stream: a still-pending value is dropped (slot holds). */
    fun finish(out: MutableList<Record>) {
        val p = pending ?: return
        pending = null
        record(Record(p.tSec, lastRecord?.bpm ?: p.bpm), out)
    }

    /** Whole-series use (History, saved stats): same rules, no delay. */
    object Offline {
        data class Estimate(val tSec: Double, val bpm: Double?, val filledFraction: Double = 0.0, val windowHasLoss: Boolean = false)

        /** [lossTimes]: times where the signal was lost (guard restarts). */
        fun run(
            estimates: List<Estimate>,
            lossTimes: List<Double> = emptyList(),
            cfg: BpmGuardConfig = BpmGuardConfig()
        ): List<Record> {
            val g = BpmGuard(cfg)
            val recs = ArrayList<Record>()
            var li = 0
            for (e in estimates) {
                while (li < lossTimes.size && lossTimes[li] <= e.tSec) { g.signalLost(lossTimes[li]); li++ }
                recs += g.onEstimate(e.tSec, e.bpm, e.filledFraction, e.windowHasLoss)
            }
            g.finish(recs)
            recs.sortBy { it.tSec }
            return slew(recs, lossTimes, cfg)
        }

        /** <= maxSlew BPM/s between consecutive records; restarts freely
         * after a loss (no ramp from a stale value). */
        fun slew(recs: List<Record>, lossTimes: List<Double> = emptyList(), cfg: BpmGuardConfig = BpmGuardConfig()): List<Record> {
            val out = ArrayList<Record>(recs.size)
            var prev: Record? = null
            var li = 0
            for (r in recs) {
                var restart = prev == null
                while (li < lossTimes.size && lossTimes[li] <= r.tSec) {
                    if (prev != null && lossTimes[li] > prev.tSec) restart = true
                    li++
                }
                val v = if (restart || prev == null) r.bpm else {
                    val step = cfg.maxSlewBpmPerSec * (r.tSec - prev.tSec)
                    val d = r.bpm - prev.bpm
                    if (abs(d) <= step) r.bpm else prev.bpm + sign(d) * step
                }
                val rec = Record(r.tSec, v)
                out += rec
                prev = rec
            }
            return out
        }
    }
}
