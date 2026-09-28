package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.model.SensorSample
import com.eee312.ppgmonitor.sensor.table.CsvTableReader
import com.eee312.ppgmonitor.sensor.table.FileSourceIntegrationTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Evidence for the dropout filler + BPM guard, on the bundled data.
 * Prints the tables used in the Phase 6 report (see build output with -i,
 * or the files in build/phase6) and asserts the acceptance rules:
 *  - on the ECG-referenced PhysioNet records the new stage makes no record
 *    worse by more than 1 BPM MAE;
 *  - on clean recordings (almost) nothing is flagged;
 *  - with injected detachments the BPM spikes are gone.
 *
 * "Before" = the raw HeartRateEstimator series (what v2 showed and saved).
 * "After"  = ProcessingChain (the app's code) + the offline guard History
 *            and the saved stats use.
 */
class Phase6EvidenceTest {

    private val appDir = FileSourceIntegrationTest.appDir()
    private val outDir = File(appDir, "build/phase6").apply { mkdirs() }
    private val report = StringBuilder()

    private fun log(s: String) { println(s); report.append(s).append('\n') }

    private fun loadSamples(id: String): List<SensorSample> {
        val f = File(appDir, "src/main/assets/datasets/$id.csv")
        val plan = FileSourceIntegrationTest.readyPlan(CsvTableReader(f.bufferedReader()))
        return FileSourceIntegrationTest.samples(CsvTableReader(f.bufferedReader()), plan)
    }

    private data class Series(val t: DoubleArray, val bpm: DoubleArray)

    /** v2 behaviour: pipeline only, every estimate taken as is. */
    private fun before(samples: List<SensorSample>, fs: Double = 25.0): Series {
        val p = PpgPipeline(fs)
        val t = ArrayList<Double>(); val b = ArrayList<Double>()
        samples.forEachIndexed { i, s -> p.process(s).newHrBpm?.let { t += i / fs; b += it } }
        return Series(t.toDoubleArray(), b.toDoubleArray())
    }

    private class AfterRun(val series: Series, val flaggedPct: Double, val lostPct: Double, val chainSlots: List<ProcessingChain.Slot>)

    /** v3: the app's chain, then the offline guard over the stored series. */
    private fun after(samples: List<SensorSample>, fs: Double = 25.0): AfterRun {
        val chain = ProcessingChain(fs, ProcessingChain.fillerFor(fs, liveUsb = false, liveHardware = false))
        val slots = samples.flatMap { chain.process(it) }
        val flags = slots.map { it.out.flags }.toIntArray()
        DropoutFiller.relabelLostRuns(flags)
        val estIdx = ArrayList<Int>(); val estBpm = ArrayList<Double>()
        slots.forEachIndexed { i, s -> s.pipe?.newHrBpm?.let { estIdx += i; estBpm += it } }
        val g = BpmSeries.guarded(slots.size, flags, estIdx.toIntArray(), estBpm.toDoubleArray(), fs)
        val bad = flags.count { it and (SampleFlags.FILLED or SampleFlags.LOST) != 0 }
        val lost = flags.count { it and SampleFlags.LOST != 0 }
        return AfterRun(
            Series(g.records.map { it.tSec }.toDoubleArray(), g.records.map { it.bpm }.toDoubleArray()),
            100.0 * bad / slots.size, 100.0 * lost / slots.size, slots
        )
    }

    private fun refSeries(id: String): List<Pair<Double, Double>>? {
        val f = File(appDir, "src/main/assets/datasets/${id}_ref_hr.csv")
        if (!f.exists()) return null
        return f.readLines().drop(1).map { it.split(',') }.map { it[0].toDouble() to it[1].toDouble() }
    }

    /** Reference for an estimate at t: mean ECG HR over the 12 s window the
     * estimate was computed from. */
    private fun refAt(ref: List<Pair<Double, Double>>, t: Double): Double? {
        val v = ref.filter { it.first > t - 12.0 && it.first <= t }.map { it.second }
        return if (v.isEmpty()) null else v.average()
    }

    private fun errors(s: Series, ref: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
        val e = s.t.indices.mapNotNull { i -> refAt(ref, s.t[i])?.let { s.bpm[i] - it } }
        val mae = e.map { abs(it) }.average()
        val rmse = sqrt(e.map { it * it }.average())
        return Triple(mae, rmse, e.average())
    }

    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    private fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)

    @Test
    fun `accuracy against ECG reference - before vs after`() {
        log("| Record | MAE before | MAE after | RMSE before | RMSE after | bias before | bias after | flagged % |")
        log("|---|---|---|---|---|---|---|---|")
        val worse = ArrayList<String>()
        for (id in listOf("s11_sit", "s11_walk", "s11_run", "s12_sit", "s12_walk", "s12_run")) {
            val samples = loadSamples(id)
            val ref = refSeries(id)!!
            val (mb, rb, bb) = errors(before(samples), ref)
            val a = after(samples)
            val (ma, ra, ba) = errors(a.series, ref)
            log("| $id | ${f1(mb)} | ${f1(ma)} | ${f1(rb)} | ${f1(ra)} | ${f1(bb)} | ${f1(ba)} | ${f2(a.flaggedPct)} |")
            val bs = before(samples)
            File(outDir, "trace_$id.txt").writeText(buildString {
                append("t,before,after,ref\n")
                for (i in bs.t.indices) {
                    val t = bs.t[i]
                    val j = a.series.t.indices.firstOrNull { abs(a.series.t[it] - t) < 1e-6 }
                    append("${f1(t)},${f1(bs.bpm[i])},${j?.let { f1(a.series.bpm[it]) } ?: ""},${refAt(ref, t)?.let { f1(it) } ?: ""}\n")
                }
            })
            if (ma > mb + 1.0) worse += "$id (${f1(mb)} -> ${f1(ma)})"
        }
        File(outDir, "accuracy.md").writeText(report.toString())
        assertTrue("records made worse by > 1 BPM MAE: $worse", worse.isEmpty())
    }

    @Test
    fun `flagged share on every bundled dataset`() {
        log("| Dataset | flagged (filled+lost) % | lost % | estimates before | after |")
        log("|---|---|---|---|---|")
        val ids = File(appDir, "src/main/assets/datasets").listFiles { f -> f.name.endsWith(".csv") && !f.name.contains("_ref_hr") }!!
            .map { it.name.removeSuffix(".csv") }.sorted()
        for (id in ids) {
            val s = loadSamples(id)
            val a = after(s)
            log("| $id | ${f2(a.flaggedPct)} | ${f2(a.lostPct)} | ${before(s).bpm.size} | ${a.series.bpm.size} |")
            if (id.startsWith("s1")) assertTrue("$id flagged ${a.flaggedPct}%", a.flaggedPct < 0.5)
        }
        File(outDir, "flagged.md").writeText(report.toString())
    }

    /** Random 0.2-0.8 s chunks of IR set near zero (a finger lifting off). */
    private fun inject(samples: List<SensorSample>, seed: Int): Pair<List<SensorSample>, List<IntRange>> {
        val rnd = Random(seed)
        val out = samples.toMutableList()
        val chunks = ArrayList<IntRange>()
        var i = 20 * 25
        while (i < samples.size - 50) {
            val len = (0.2 + rnd.nextDouble() * 0.6).let { (it * 25).toInt().coerceAtLeast(5) }
            for (k in i until minOf(samples.size, i + len)) {
                out[k] = out[k].copy(ir = 1500 + rnd.nextInt(500), red = 1200 + rnd.nextInt(400))
            }
            chunks += i until i + len
            i += (12 + rnd.nextInt(10)) * 25
        }
        return out to chunks
    }

    @Test
    fun `injected detachments on hw_sep16 and hw_walk - spikes gone`() {
        for ((id, seed) in listOf("hw_sep16" to 7, "hw_walk" to 11)) {
            val clean = loadSamples(id)
            val cleanRef = after(clean).series
            val (bad, chunks) = inject(clean, seed)
            val b = before(bad)
            val a = after(bad)
            fun dev(s: Series): List<Double> = s.t.indices.map { i ->
                // compare with the clean run's guarded value at the same time
                val j = cleanRef.t.indices.minByOrNull { abs(cleanRef.t[it] - s.t[i]) }!!
                abs(s.bpm[i] - cleanRef.bpm[j])
            }
            val db = dev(b); val da = dev(a.series)
            // One-off jumps: > 10 BPM away from the median of the 2 estimates
            // on either side (what a momentary detachment produces).
            fun spikes(s: Series): Int = (2 until s.bpm.size - 2).count { i ->
                val nb = listOf(s.bpm[i - 2], s.bpm[i - 1], s.bpm[i + 1], s.bpm[i + 2]).sorted()
                abs(s.bpm[i] - (nb[1] + nb[2]) / 2) > 10
            }
            log("")
            log("### $id - ${chunks.size} injected detachments (${chunks.joinToString { f1(it.first / 25.0) + "s/" + f2((it.last - it.first + 1) / 25.0) + "s" }})")
            val cleanRaw = before(clean)
            log("| | estimates | one-off spikes (>10 BPM) | max deviation from clean run | deviations > 10 BPM | mean abs deviation |")
            log("|---|---|---|---|---|---|")
            log("| clean, before (v2) | ${cleanRaw.bpm.size} | ${spikes(cleanRaw)} | - | - | - |")
            log("| injected, before (v2) | ${b.bpm.size} | ${spikes(b)} | ${f1(db.max())} | ${db.count { it > 10 }} | ${f2(db.average())} |")
            log("| injected, after (v3) | ${a.series.bpm.size} | ${spikes(a.series)} | ${f1(da.max())} | ${da.count { it > 10 }} | ${f2(da.average())} |")
            log("")
            log("BPM trace (time s: before / after / clean):")
            val sb = StringBuilder()
            for (i in b.t.indices) {
                val t = b.t[i]
                val aj = a.series.t.indices.minByOrNull { abs(a.series.t[it] - t) }
                val cj = cleanRef.t.indices.minByOrNull { abs(cleanRef.t[it] - t) }!!
                val av = if (aj != null && abs(a.series.t[aj] - t) < 1.1) f1(a.series.bpm[aj]) else "-"
                sb.append("${f1(t)}: ${f1(b.bpm[i])} / $av / ${f1(cleanRef.bpm[cj])}\n")
            }
            File(outDir, "trace_$id.txt").writeText(sb.toString())
            assertTrue("$id: after still has ${da.count { it > 10 }} deviations > 10 BPM", da.count { it > 10 } <= db.count { it > 10 })
            assertTrue("$id: ${spikes(a.series)} one-off spikes remain", spikes(a.series) == 0)
        }
        File(outDir, "injected.md").writeText(report.toString())
    }

    /** Golden BPM/SQI series through the NEW chain (dropout filler in front
     * of the untouched pipeline). Nothing is rewritten - a dataset whose
     * output changes is listed so the difference can be reported. */
    @Test
    fun `golden series through the new chain - which datasets change`() {
        val golden = File(appDir, "src/test/resources/golden")
        log("| Dataset | slots | golden rows | filled/inserted/lost slots | estimates changed |")
        log("|---|---|---|---|---|")
        val changed = ArrayList<String>()
        for (g in golden.listFiles { f -> !f.name.startsWith("spo2") && f.name.endsWith(".csv") && !f.name.contains(".actual") }!!.sorted()) {
            val id = g.name.removeSuffix(".csv")
            val rows = g.readLines().drop(1).map { it.split(',') }
            val a = after(loadSamples(id))
            val slots = a.chainSlots
            val flagged = slots.count { it.out.flags and (SampleFlags.FILLED or SampleFlags.LOST or SampleFlags.INSERTED) != 0 }
            val goldEst = rows.mapIndexedNotNull { i, r -> r[3].takeIf { it.isNotEmpty() }?.let { i to it.toDouble() } }
            val newEst = slots.mapIndexedNotNull { i, s -> s.pipe?.newHrBpm?.let { i to it } }
            val nChanged = if (slots.size == rows.size && flagged == 0 && slots.all { it.out.flags and SampleFlags.ACC_BAD == 0 }) {
                goldEst.zip(newEst).count { (x, y) -> x != y } + abs(goldEst.size - newEst.size)
            } else {
                val gm = goldEst.toMap()
                newEst.count { (i, v) -> gm[i]?.let { abs(it - v) > 1e-9 } ?: true } + abs(goldEst.size - newEst.size)
            }
            log("| $id | ${slots.size} | ${rows.size} | $flagged | $nChanged of ${goldEst.size} |")
            if (nChanged > 0) changed += id
        }
        log("")
        log("Changed: $changed")
        File(outDir, "golden_new_chain.md").writeText(report.toString())
    }
}
