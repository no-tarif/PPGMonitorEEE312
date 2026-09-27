package com.eee312.ppgmonitor.sensor.table

import com.eee312.ppgmonitor.dsp.SimpleFFT
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** How a file's time column is written. */
enum class TimeUnit { SECONDS, MILLISECONDS, DATETIME }

/**
 * Which column holds what (0-based indexes into the header; null = not in
 * the file). [ir] is the PPG channel that feeds heart rate; [red] is only
 * used for SpO2 when [redIsWavelength] (a column literally named red, not
 * a channel picked by signal quality).
 */
data class ColumnMapping(
    val time: Int?,
    val ir: Int,
    val red: Int?,
    val green: Int?,
    val accX: Int?,
    val accY: Int?,
    val accZ: Int?,
    val timeUnit: TimeUnit = TimeUnit.SECONDS,
    val redIsWavelength: Boolean = true
) {
    val hasAccel: Boolean get() = accX != null && accY != null && accZ != null
}

/** Result of looking at a file's header. */
data class Detection(
    val header: List<String>,
    /** Null when the PPG column can't be identified from the names alone
     * (-> PhysioNet pleth choice, or ask the user). */
    val mapping: ColumnMapping?,
    /** Several PPG channels with no ir/red names (PhysioNet pleth_1..6):
     * the two best are chosen by in-band SNR once data is read. */
    val plethCandidates: List<Int> = emptyList(),
    /** Best guesses for the other columns, also when [mapping] is null
     * (pre-fills the mapping dialog / completes the pleth mapping). */
    val guess: ColumnMapping = ColumnMapping(null, -1, null, null, null, null, null)
)

/** What [SignalFileAnalyzer.plan] needs from the user, if anything. */
sealed class PlanResult {
    data class Ready(val plan: FilePlan) : PlanResult()
    /** The PPG column couldn't be identified: show the mapping dialog. */
    data class NeedMapping(val header: List<String>, val suggestion: ColumnMapping) : PlanResult()
    /** No usable time column: ask for the sample rate. */
    data class NeedRate(val mapping: ColumnMapping) : PlanResult()
}

/** Everything needed to play a file (see [SignalFileAnalyzer]). */
data class FilePlan(
    val header: List<String>,
    val mapping: ColumnMapping,
    /** Rate of the file itself (from its time column, or given by the user). */
    val fileRateHz: Double,
    /** Integer decimation stages applied before playback (empty = none). */
    val decimation: List<Int>,
    /** Rate the pipeline sees after decimation. */
    val playbackRateHz: Double,
    /** Multiplier applied to PPG values that were not integer counts. */
    val ppgScale: Double = 1.0,
    val notes: List<String> = emptyList()
) {
    val hasRed: Boolean get() = mapping.red != null
    val spo2Eligible: Boolean get() = mapping.red != null && mapping.redIsWavelength
}

object ColumnDetector {

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val TIME = listOf("time", "times", "timestamp", "tsec", "times", "timesec", "timeseconds",
        "sampletime", "t", "timems", "tms", "millis", "milliseconds", "ms", "datetime", "date")
    private val IR = listOf("ir", "infrared", "irraw", "irppg", "ppgir", "ppg", "rawppg", "ppg0", "ppg1", "bvp", "pleth", "plethysmogram")
    private val RED = listOf("red", "redraw", "redppg", "ppgred", "rppg")
    private val GREEN = listOf("green", "greenraw", "greenppg", "ppggreen")
    private val AX = listOf("accx", "ax", "accelx", "accelerometerx", "xacc", "ax", "x")
    private val AY = listOf("accy", "ay", "accely", "accelerometery", "yacc", "y")
    private val AZ = listOf("accz", "az", "accelz", "accelerometerz", "zacc", "z")

    fun detect(header: List<String>): Detection {
        val n = header.map(::norm)
        fun find(keys: List<String>): Int? {
            for (k in keys) { val i = n.indexOf(k); if (i >= 0) return i }
            return null
        }
        val time = find(TIME) ?: n.indexOfFirst { it.startsWith("time") }.takeIf { it >= 0 }
        val accX = find(AX)
        val accY = find(AY)
        val accZ = find(AZ)
        val green = find(GREEN)
        val red = find(RED)
        val ir = find(IR)
        val timeUnit = if (time != null && Regex("(^|[^a-z])(ms|msec|millis|milliseconds)").containsMatchIn(header[time].lowercase()))
            TimeUnit.MILLISECONDS else TimeUnit.SECONDS
        val pleths = n.indices.filter { n[it].matches(Regex("pleth\\d+")) }

        val guess = ColumnMapping(time, ir ?: -1, red, green, accX, accY, accZ, timeUnit)
        if (ir == null && pleths.size >= 2) {
            return Detection(header, null, pleths, guess)
        }
        if (ir == null) {
            // No IR name: a lone red (or single pleth) channel still gives heart rate.
            val lone = red ?: pleths.firstOrNull() ?: return Detection(header, null, emptyList(), guess)
            return Detection(header, guess.copy(ir = lone, red = null), emptyList(), guess)
        }
        return Detection(header, guess, emptyList(), guess)
    }
}

/** Numeric cell parsing tolerant of decimal commas and NaN/blank. */
object Cells {
    fun number(s: String?): Double {
        if (s == null) return Double.NaN
        var t = s.trim()
        if (t.isEmpty() || t.equals("nan", true) || t.equals("null", true) || t.equals("na", true)) return Double.NaN
        if (t.indexOf('.') < 0 && t.count { it == ',' } == 1) t = t.replace(',', '.')
        return t.toDoubleOrNull() ?: Double.NaN
    }

    private val DATETIME: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd")
        .optionalStart().appendLiteral(' ').optionalEnd()
        .optionalStart().appendLiteral('T').optionalEnd()
        .appendPattern("HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter()

    /** Seconds (absolute for DATETIME) or NaN. */
    fun time(s: String?, unit: TimeUnit): Double {
        if (s == null) return Double.NaN
        return when (unit) {
            TimeUnit.SECONDS -> number(s)
            TimeUnit.MILLISECONDS -> number(s) / 1000.0
            TimeUnit.DATETIME -> try {
                val dt = LocalDateTime.parse(s.trim(), DATETIME)
                dt.toEpochSecond(ZoneOffset.UTC) + dt.nano / 1e9
            } catch (e: Exception) {
                Double.NaN
            }
        }
    }
}

/**
 * Looks at the first rows of a file to finish the plan: time unit and
 * sample rate (median time step), PhysioNet channel choice, value scale,
 * and whether to decimate a high-rate file down to the pipeline's rate.
 */
object SignalFileAnalyzer {
    const val TARGET_RATE_HZ = 25.0
    /** Files above this rate are decimated (the pipeline is built for ~25 Hz). */
    const val MAX_DIRECT_RATE_HZ = 60.0
    private const val PREFIX_ROWS = 20_000

    /**
     * Builds the playback plan from the header and the first rows.
     * [userMapping] / [userRateHz] are the answers to a previous
     * NeedMapping / NeedRate.
     */
    fun plan(
        header: List<String>,
        prefix: List<List<String>>,
        userMapping: ColumnMapping? = null,
        userRateHz: Double? = null
    ): PlanResult {
        val notes = ArrayList<String>()
        val det = ColumnDetector.detect(header)
        var mapping: ColumnMapping? = userMapping ?: det.mapping

        // Time unit first (the pleth choice needs the rate).
        fun withUnit(m: ColumnMapping) = m.copy(timeUnit = timeUnitOf(m, prefix))
        val guess = withUnit(det.guess)
        if (mapping == null && det.plethCandidates.size >= 2) {
            val rate = rateFromTime(guess, prefix) ?: userRateHz ?: return PlanResult.NeedRate(guess.copy(ir = det.plethCandidates[0]))
            val (ir, red) = pickPleth(det.plethCandidates, prefix, rate)
            mapping = guess.copy(ir = ir, red = red, redIsWavelength = false)
            notes += "PPG channels picked by signal quality: ir = ${header[ir]}, red = ${header[red]} " +
                "(not real 880/660 nm wavelengths, so SpO2 is not computed)"
        }
        if (mapping == null) return PlanResult.NeedMapping(header, guess)
        mapping = withUnit(mapping)

        val fileRate = rateFromTime(mapping, prefix) ?: userRateHz ?: return PlanResult.NeedRate(mapping)
        if (mapping.time == null) notes += "No time column - played at the %.2f Hz you entered".format(fileRate)
        val stages = decimationStages(fileRate)
        val playbackRate = fileRate / stages.fold(1) { a, b -> a * b }
        if (stages.isNotEmpty()) {
            notes += "Recorded at %.0f Hz - resampled to %.2f Hz (FIR decimation %s, as tools/prepare_datasets.py)"
                .format(fileRate, playbackRate, stages.joinToString(" x "))
        }
        val scale = ppgScale(mapping, prefix)
        if (scale != 1.0) notes += "PPG values are not integer counts - scaled x%.0f".format(scale)
        if (!mapping.hasAccel) notes += "No accelerometer columns - motion cancellation (LMS) is bypassed"
        if (mapping.red == null) notes += "No red channel - SpO2 N/A"
        return PlanResult.Ready(FilePlan(header, mapping, fileRate, stages, playbackRate, scale, notes))
    }

    fun readPrefix(reader: TableReader, rows: Int = PREFIX_ROWS): List<List<String>> {
        val out = ArrayList<List<String>>()
        while (out.size < rows) out += reader.nextRow() ?: break
        return out
    }

    /** Time unit of the mapped time column from its first values. */
    fun timeUnitOf(mapping: ColumnMapping, prefix: List<List<String>>): TimeUnit {
        val t = mapping.time ?: return mapping.timeUnit
        val firstVal = prefix.firstOrNull { it[t].isNotBlank() }?.get(t) ?: return mapping.timeUnit
        if (Cells.number(firstVal).isNaN() && HeaderDetect.looksLikeDateTime(firstVal)) return TimeUnit.DATETIME
        if (mapping.timeUnit == TimeUnit.MILLISECONDS) return TimeUnit.MILLISECONDS
        val dt = medianDt(prefix.map { Cells.number(it[t]) })
        // A PPG file sampled slower than 2 Hz makes no sense: steps of >= 0.5
        // "seconds" are milliseconds.
        return if (dt != null && dt >= 0.5) TimeUnit.MILLISECONDS else TimeUnit.SECONDS
    }

    fun medianDt(times: List<Double>): Double? {
        val dts = ArrayList<Double>()
        for (i in 1 until times.size) {
            val d = times[i] - times[i - 1]
            if (d.isFinite() && d > 0) dts += d
        }
        if (dts.size < 5) return null
        dts.sort()
        return dts[dts.size / 2]
    }

    /** Rate from the time column, or null if there is none / it is unusable. */
    fun rateFromTime(mapping: ColumnMapping, prefix: List<List<String>>): Double? {
        val t = mapping.time ?: return null
        val dt = medianDt(prefix.map { Cells.time(it[t], mapping.timeUnit) }) ?: return null
        return 1.0 / dt
    }

    /** Integer stages (each <= 10) reducing [rate] to about [TARGET_RATE_HZ]
     * - 500 Hz -> (5, 4) exactly as tools/prepare_datasets.py. */
    fun decimationStages(rate: Double): List<Int> {
        if (rate <= MAX_DIRECT_RATE_HZ) return emptyList()
        var q = round(rate / TARGET_RATE_HZ).toInt().coerceAtLeast(2)
        val stages = ArrayList<Int>()
        for (f in intArrayOf(10, 9, 8, 7, 6, 5, 4, 3, 2)) {
            // prefer the (5, 4) split for 20 like prepare_datasets.py
            if (q == 20) { stages += 5; stages += 4; q = 1; break }
            while (q % f == 0 && q > 1) { stages += f; q /= f }
        }
        if (q > 1) stages += q
        return stages
    }

    /** In-band (0.5-4 Hz) over out-of-band power, like prepare_datasets.py's band_snr. */
    fun bandSnr(x: DoubleArray, fs: Double): Double {
        val seg = minOf(4096, SimpleFFT.nextPowerOfTwo(x.size) / 2).coerceAtLeast(16)
        var pin = 0.0
        var pout = 0.0
        var start = 0
        val win = DoubleArray(seg) { 0.5 - 0.5 * cos(2 * PI * it / (seg - 1)) }
        while (start + seg <= x.size) {
            var mean = 0.0
            for (i in 0 until seg) mean += x[start + i]
            mean /= seg
            val re = DoubleArray(seg) { (x[start + it] - mean) * win[it] }
            val im = DoubleArray(seg)
            SimpleFFT.fft(re, im)
            for (k in 1..seg / 2) {
                val f = k * fs / seg
                val p = re[k] * re[k] + im[k] * im[k]
                if (f in 0.5..4.0) pin += p else pout += p
            }
            start += seg / 2
        }
        return if (pout > 0) pin / pout else Double.POSITIVE_INFINITY
    }

    /** Two best pleth channels by band SNR on the prefix -> (ir, red). */
    fun pickPleth(candidates: List<Int>, prefix: List<List<String>>, fs: Double): Pair<Int, Int> {
        val ranked = candidates.map { c ->
            val x = prefix.map { Cells.number(it[c]) }.filter { it.isFinite() }.toDoubleArray()
            c to bandSnr(x, fs)
        }.sortedByDescending { it.second }
        return ranked[0].first to ranked[1].first
    }

    /** PPG values that are not integer counts (e.g. normalised 0..1 or
     * volts) are scaled so they survive the integer ADC-count model. */
    fun ppgScale(mapping: ColumnMapping, prefix: List<List<String>>): Double {
        val v = prefix.map { Cells.number(it[mapping.ir]) }.filter { it.isFinite() }
        if (v.isEmpty()) return 1.0
        val nonInteger = v.any { it != Math.rint(it) }
        val maxAbs = v.maxOf { Math.abs(it) }
        return if (nonInteger && maxAbs < 10_000) 1000.0 else 1.0
    }
}

/**
 * Streaming FIR decimator by an integer factor, the design
 * scipy.signal.decimate(x, q, ftype='fir', zero_phase=True) uses (and so
 * tools/prepare_datasets.py): Hamming-windowed sinc, 20q+1 taps, cutoff
 * at the new Nyquist, applied centred (zero phase) with zeros beyond the
 * ends, keeping every q-th sample. Latency (taps-1)/2 input samples.
 */
class FirDecimator(private val q: Int) {
    private val taps: DoubleArray
    private val half: Int
    private val buf: DoubleArray
    private var count = 0L          // inputs pushed
    private var nextOut = 0L        // index (in input samples) of the next output centre

    init {
        val n = 20 * q + 1
        half = (n - 1) / 2
        val fc = 1.0 / q
        taps = DoubleArray(n) { k ->
            val m = k - half
            val sinc = if (m == 0) 1.0 else sin(PI * fc * m) / (PI * fc * m)
            val w = 0.54 - 0.46 * cos(2 * PI * k / (n - 1))
            fc * sinc * w
        }
        val sum = taps.sum()
        for (i in taps.indices) taps[i] /= sum
        buf = DoubleArray(n)
    }

    /** Push one input; returns the outputs that became computable. */
    fun push(x: Double, out: (Double) -> Unit) {
        buf[(count % buf.size).toInt()] = x
        count++
        while (nextOut + half < count) {
            out(outputAt(nextOut, count))
            nextOut += q
        }
    }

    /** Flush at end of input (zeros beyond the end). */
    fun finish(out: (Double) -> Unit) {
        while (nextOut < count) {
            out(outputAt(nextOut, count))
            nextOut += q
        }
    }

    private fun outputAt(centre: Long, available: Long): Double {
        var acc = 0.0
        for (k in taps.indices) {
            val idx = centre + half - k
            if (idx < 0 || idx >= available || idx < available - buf.size) continue
            acc += taps[k] * buf[(idx % buf.size).toInt()]
        }
        return acc
    }
}
