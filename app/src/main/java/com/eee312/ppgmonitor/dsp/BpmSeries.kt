package com.eee312.ppgmonitor.dsp

/**
 * Builds the guarded BPM series of a whole recording from what is stored
 * per sample: the raw estimator outputs (sample index + value) and the
 * dropout flags. Used by SessionRecorder (saved avg/min/max), the History
 * detail screen (graph + stats, also for readings recorded before v3,
 * whose flags are all null) and the Phase 6 evidence tests. Sample i is
 * at time i / fsHz.
 */
object BpmSeries {

    data class Result(val records: List<BpmGuard.Record>, val lossTimes: List<Double>) {
        val avg: Double? get() = records.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()
        val min: Double? get() = records.minOfOrNull { it.bpm }
        val max: Double? get() = records.maxOfOrNull { it.bpm }
    }

    /**
     * @param n number of samples
     * @param flags per-sample SampleFlags (null = none recorded)
     * @param estIndex sample index of each raw HR estimate (ascending)
     * @param estBpm the raw estimate at that index
     */
    fun guarded(
        n: Int,
        flags: IntArray?,
        estIndex: IntArray,
        estBpm: DoubleArray,
        fsHz: Double,
        windowSec: Double = 12.0,
        cfg: BpmGuardConfig = BpmGuardConfig()
    ): Result {
        val win = Math.round(windowSec * fsHz).toInt().coerceAtLeast(1)
        val badPrefix = IntArray(n + 1)
        val lostPrefix = IntArray(n + 1)
        val lossTimes = ArrayList<Double>()
        var inLost = false
        for (i in 0 until n) {
            val f = flags?.get(i) ?: 0
            val lost = f and SampleFlags.LOST != 0
            val bad = f and (SampleFlags.FILLED or SampleFlags.LOST) != 0
            badPrefix[i + 1] = badPrefix[i] + if (bad) 1 else 0
            lostPrefix[i + 1] = lostPrefix[i] + if (lost) 1 else 0
            if (lost && !inLost) lossTimes += i / fsHz
            inLost = lost
        }
        val est = ArrayList<BpmGuard.Offline.Estimate>(estIndex.size)
        for (k in estIndex.indices) {
            val i = estIndex[k].coerceIn(0, maxOf(0, n - 1))
            val from = maxOf(0, i + 1 - win)
            val len = (i + 1 - from).coerceAtLeast(1)
            val filled = if (n == 0) 0.0 else (badPrefix[i + 1] - badPrefix[from]).toDouble() / len
            val hasLoss = n > 0 && lostPrefix[i + 1] - lostPrefix[from] > 0
            est += BpmGuard.Offline.Estimate(estIndex[k] / fsHz, estBpm[k], filled, hasLoss)
        }
        return Result(BpmGuard.Offline.run(est, lossTimes, cfg), lossTimes)
    }
}
