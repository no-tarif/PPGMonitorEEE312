package com.eee312.ppgmonitor.sensor.table

import com.eee312.ppgmonitor.dsp.PpgPipeline
import com.eee312.ppgmonitor.dsp.spo2.Spo2Engine
import com.eee312.ppgmonitor.dsp.spo2.Spo2Result
import com.eee312.ppgmonitor.history.RawCsvExporter
import com.eee312.ppgmonitor.history.RawExportRow
import com.eee312.ppgmonitor.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.io.StringWriter

class FileSourceIntegrationTest {

    companion object {
        val COURSE = File("E:/Study Materials/3-1/312/Project")

        fun appDir(): File {
            var dir = File(".").absoluteFile
            repeat(6) {
                if (File(dir, "src/main/assets/datasets").exists()) return dir
                if (File(dir, "app/src/main/assets/datasets").exists()) return File(dir, "app")
                dir = dir.parentFile ?: return@repeat
            }
            error("app dir not found")
        }

        fun readyPlan(r: TableReader, userMapping: ColumnMapping? = null, rate: Double? = null): FilePlan {
            val prefix = SignalFileAnalyzer.readPrefix(r)
            val res = SignalFileAnalyzer.plan(r.header, prefix, userMapping, rate)
            assertTrue("expected a ready plan, got $res", res is PlanResult.Ready)
            return (res as PlanResult.Ready).plan
        }

        /** Same conversion CsvDatasetSource does. */
        fun samples(reader: TableReader, plan: FilePlan): List<SensorSample> {
            val out = ArrayList<SensorSample>()
            PlaybackRowStream(reader, plan).use { rows ->
                while (rows.hasNext()) {
                    val row = rows.next()
                    val ir = PlaybackRowStream.counts(row.ir) ?: continue
                    out += SensorSample(0L, ir, PlaybackRowStream.counts(row.red), row.accX, row.accY, row.accZ,
                        PlaybackRowStream.counts(row.green), row.t)
                }
            }
            return out
        }
    }

    private fun detect(header: String) = ColumnDetector.detect(header.split(','))

    @Test
    fun `header detection for every known layout`() {
        // bundled datasets + walk_clean / class_1_clean + the canonical export
        detect("time,ir,red,accX,accY,accZ").mapping!!.let {
            assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf(it.time, it.ir, it.red, it.accX, it.accY, it.accZ))
            assertNull(it.green)
        }
        detect("time,ir,red,accX,accY,accZ,green").mapping!!.let { assertEquals(6, it.green) }
        // sep16_capture.csv
        detect("time_s,ir,red,green,accX,accY,accZ").mapping!!.let {
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), listOf(it.time, it.ir, it.red, it.green, it.accX, it.accY, it.accZ))
        }
        // Running_2.csv (the app's old export): one optical channel, no axes
        detect("tSec,rawPpg,cleanedPpg,accMagnitude,bpm").mapping!!.let {
            assertEquals(0, it.time); assertEquals(1, it.ir); assertNull(it.red); assertTrue(!it.hasAccel)
        }
        // PhysioNet originals: pleth_1..6 -> chosen from the data
        val pn = detect("time,ecg,peaks,pleth_1,pleth_2,pleth_3,pleth_4,pleth_5,pleth_6,lc_1,lc_2,temp_1,temp_2,temp_3,a_x,a_y,a_z,g_x,g_y,g_z")
        assertNull(pn.mapping)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), pn.plethCandidates)
        assertEquals(listOf(0, 14, 15, 16), listOf(pn.guess.time, pn.guess.accX, pn.guess.accY, pn.guess.accZ))
    }

    @Test
    fun `every bundled dataset plays exactly as before`() {
        val dir = File(appDir(), "src/main/assets/datasets")
        val files = dir.listFiles { f -> f.name.endsWith(".csv") && !f.name.contains("_ref_hr") }!!
        assertTrue(files.size >= 10)
        for (f in files) {
            val plan = readyPlan(CsvTableReader(f.bufferedReader()))
            assertEquals(f.name, 25.0, plan.fileRateHz, 0.01)
            assertTrue(plan.decimation.isEmpty())
            val got = samples(CsvTableReader(f.bufferedReader()), plan)
            // The pre-v3 parser: split + toInt / toFloat.
            val lines = f.readLines().drop(1)
            assertEquals(f.name, lines.size, got.size)
            for ((i, line) in lines.withIndex()) {
                val c = line.split(',')
                val s = got[i]
                assertEquals(c[1].toInt(), s.ir)
                assertEquals(c[2].takeIf { it.isNotEmpty() }?.toInt(), s.red)
                assertEquals(c[3].toFloat(), s.accX, 0f)
                assertEquals(c[4].toFloat(), s.accY, 0f)
                assertEquals(c[5].toFloat(), s.accZ, 0f)
                if (c.size == 7) assertEquals(c[6].toInt(), s.green)
            }
        }
    }

    @Test
    fun `capture files in the course folder are detected with the right rate`() {
        assumeTrue(COURSE.exists())
        val expected = mapOf(
            "class_1_clean.csv" to 25.0, "walk_clean.csv" to 25.0,
            "sep16_capture.csv" to 50.0 / 3, "Running_2.csv" to 25.0
        )
        for ((name, rate) in expected) {
            val f = File(COURSE, name)
            val plan = readyPlan(CsvTableReader(f.bufferedReader()))
            assertEquals(name, rate, plan.fileRateHz, 0.05)
            val n = samples(CsvTableReader(f.bufferedReader()), plan).size
            assertTrue("$name: $n samples", n > 3000)
        }
    }

    @Test
    fun `PhysioNet 500 Hz original is decimated like prepare_datasets and matches the bundled asset`() {
        val src = File(COURSE, "Data Sets/S12/s12_sit.csv")
        assumeTrue(src.exists())
        val auto = readyPlan(CsvTableReader(src.bufferedReader()))
        assertEquals(500.0, auto.fileRateHz, 0.5)
        assertEquals(listOf(5, 4), auto.decimation)
        assertEquals(25.0, auto.playbackRateHz, 0.05)
        assertTrue(!auto.spo2Eligible)
        println("s12_sit auto-picked ir=${auto.header[auto.mapping.ir]} red=${auto.header[auto.mapping.red!!]}")

        // Same channels as the asset (datasets.json: ir<-pleth_2, red<-pleth_1).
        val h = auto.header
        val forced = auto.mapping.copy(ir = h.indexOf("pleth_2"), red = h.indexOf("pleth_1"))
        val plan = readyPlan(CsvTableReader(src.bufferedReader()), forced)
        val got = samples(CsvTableReader(src.bufferedReader()), plan)
        val asset = File(appDir(), "src/main/assets/datasets/s12_sit.csv").readLines().drop(1).map { it.split(',') }
        assertEquals(asset.size.toDouble(), got.size.toDouble(), 2.0)
        var maxRel = 0.0
        for (i in 10 until asset.size - 10) {
            val rel = Math.abs(got[i].ir - asset[i][1].toInt()) / asset[i][1].toDouble()
            maxRel = maxOf(maxRel, rel)
        }
        println("s12_sit decimated vs asset: max relative ir difference %.2e".format(maxRel))
        assertTrue("max rel diff $maxRel", maxRel < 1e-3)
    }

    @Test
    fun `export then upload reproduces the reading`() {
        val f = File(appDir(), "src/main/assets/datasets/hw_sep16.csv")
        val plan0 = readyPlan(CsvTableReader(f.bufferedReader()))
        val original = samples(CsvTableReader(f.bufferedReader()), plan0)

        // What SessionRecorder stores for these samples, then Export.
        val rows = original.mapIndexed { i, s ->
            RawExportRow(i + 1L, i / 25.0, s.sourceTimeSec, s.ir.toDouble(), s.ir, s.red, s.green,
                s.accX.toDouble(), s.accY.toDouble(), s.accZ.toDouble(), 0)
        }
        val w = StringWriter()
        w.write(RawCsvExporter.header(true))
        RawCsvExporter.writeRows(w, rows, true)

        val plan1 = readyPlan(CsvTableReader(StringReader(w.toString())))
        assertEquals(25.0, plan1.fileRateHz, 1e-6)
        val uploaded = samples(CsvTableReader(StringReader(w.toString())), plan1)
        assertEquals(original.size, uploaded.size)

        fun stats(ss: List<SensorSample>): List<Double> {
            val p = PpgPipeline(25.0)
            val bpm = ss.mapNotNull { p.process(it).newHrBpm }
            val spo2 = Spo2Engine.estimate(
                DoubleArray(ss.size) { ss[it].ir.toDouble() }, DoubleArray(ss.size) { ss[it].red!!.toDouble() }, 25.0
            ) as Spo2Result.Available
            return listOf(bpm.average(), bpm.min(), bpm.max(), spo2.summary.medianR, spo2.summary.accepted.toDouble())
        }
        val a = stats(original)
        val b = stats(uploaded)
        println("original avg/min/max BPM, median R, accepted: $a\nuploaded: $b")
        for (k in a.indices) assertEquals(a[k], b[k], 1e-9)
        assertNotNull(plan1.mapping.green)
    }
}
