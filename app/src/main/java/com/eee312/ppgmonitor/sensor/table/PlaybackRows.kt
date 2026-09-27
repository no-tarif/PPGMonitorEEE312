package com.eee312.ppgmonitor.sensor.table

import java.io.Closeable
import kotlin.math.round

/** One row to play: time in seconds from the file start, channels as
 * numbers; NaN = missing in the file (blank / NaN cell). Accelerometer
 * values stay Float as parsed so bundled datasets play back exactly as
 * before (no double -> float re-rounding). */
data class PlaybackRow(
    val t: Double,
    val ir: Double,
    val red: Double,
    val green: Double,
    val accX: Float,
    val accY: Float,
    val accZ: Float
)

/**
 * Turns a [TableReader] + [FilePlan] into [PlaybackRow]s, streaming:
 * picks the mapped columns, converts the time column (seconds, ms or
 * date-time, rebased to 0; row index / rate when there is none), applies
 * the PPG scale, and decimates high-rate files. During decimation a blank
 * cell is bridged with the channel's previous value, and the output row is
 * marked missing (NaN) when the input at its centre was missing.
 */
class PlaybackRowStream(private val reader: TableReader, private val plan: FilePlan) : Iterator<PlaybackRow>, Closeable {

    private val m = plan.mapping
    private var index = 0L
    private var t0 = Double.NaN
    private val queue = ArrayDeque<PlaybackRow>()
    private var eof = false

    // Decimation: channels ir, red, green, ax, ay, az, missing-indicator.
    private val stages = plan.decimation
    private val chains: List<List<FirDecimator>> = List(7) { stages.map { FirDecimator(it) } }
    private val last = DoubleArray(6) { Double.NaN }
    private val outBuf = Array(7) { ArrayDeque<Double>() }
    private var outIndex = 0L
    private val totalQ = stages.fold(1) { a, b -> a * b }

    private fun floatCell(s: String?): Float {
        if (s == null) return Float.NaN
        var t = s.trim()
        if (t.isEmpty() || t.equals("nan", true)) return Float.NaN
        if (t.indexOf('.') < 0 && t.count { it == ',' } == 1) t = t.replace(',', '.')
        return t.toFloatOrNull() ?: Float.NaN
    }

    private fun cell(r: List<String>, i: Int?): String? = i?.let { r.getOrNull(it) }

    private fun parse(r: List<String>): PlaybackRow {
        val rawT = m.time?.let { Cells.time(r[it], m.timeUnit) } ?: Double.NaN
        val t = if (m.time == null) index / plan.fileRateHz else {
            if (t0.isNaN() && rawT.isFinite()) t0 = rawT
            rawT - t0
        }
        index++
        val ir = Cells.number(cell(r, m.ir)) * plan.ppgScale
        val red = cell(r, m.red)?.let { Cells.number(it) * plan.ppgScale } ?: Double.NaN
        val green = cell(r, m.green)?.let { Cells.number(it) * plan.ppgScale } ?: Double.NaN
        return PlaybackRow(t, ir, red, green, floatCell(cell(r, m.accX)), floatCell(cell(r, m.accY)), floatCell(cell(r, m.accZ)))
    }

    private fun fill() {
        while (queue.isEmpty() && !eof) {
            val r = reader.nextRow()
            if (r == null) {
                eof = true
                if (stages.isNotEmpty()) flushDecimators()
                break
            }
            val row = parse(r)
            if (stages.isEmpty()) queue.addLast(row) else decimate(row)
        }
    }

    private fun decimate(row: PlaybackRow) {
        val vals = doubleArrayOf(row.ir, row.red, row.green, row.accX.toDouble(), row.accY.toDouble(), row.accZ.toDouble())
        for (c in 0 until 6) {
            val v = vals[c]
            val x = if (v.isFinite()) { last[c] = v; v } else if (last[c].isFinite()) last[c] else Double.NaN
            push(c, x)
        }
        push(6, if (row.ir.isFinite()) 0.0 else 1.0)
        drain()
    }

    private fun push(c: Int, x: Double) {
        pushStage(c, 0, x)
    }

    private fun pushStage(c: Int, s: Int, x: Double) {
        if (s == stages.size) { outBuf[c].addLast(x); return }
        chains[c][s].push(x) { y -> pushStage(c, s + 1, y) }
    }

    private fun flushStage(c: Int, s: Int) {
        if (s == stages.size) return
        chains[c][s].finish { y -> pushStage(c, s + 1, y) }
        flushStage(c, s + 1)
    }

    private fun flushDecimators() {
        for (c in 0 until 7) flushStage(c, 0)
        drain()
    }

    private fun drain() {
        while (outBuf.all { it.isNotEmpty() }) {
            val v = DoubleArray(7) { outBuf[it].removeFirst() }
            val t = outIndex * totalQ / plan.fileRateHz
            outIndex++
            val missing = v[6] > 0.5
            queue.addLast(
                PlaybackRow(
                    t,
                    if (missing) Double.NaN else v[0],
                    v[1], v[2], v[3].toFloat(), v[4].toFloat(), v[5].toFloat()
                )
            )
        }
    }

    override fun hasNext(): Boolean { fill(); return queue.isNotEmpty() }

    override fun next(): PlaybackRow {
        fill()
        return queue.removeFirst()
    }

    override fun close() = reader.close()

    companion object {
        /** Integer ADC-count value for a channel, or null when missing. */
        fun counts(v: Double): Int? = if (v.isFinite()) round(v).toInt() else null
    }
}
