package com.eee312.ppgmonitor.dsp.spo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Regression test against tools/spo2_reference.py's output (the Python
 * port of estimate_spo2.m) - the ground truth this engine must match to
 * within 1e-6, per Phase 2's spec. Also covers the edge cases spelled
 * out there: short recording, non-positive DC, flat signal, single
 * wavelength.
 */
class Spo2EngineTest {

    private fun findAppDir(): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            if (File(dir, "src/main/assets/datasets/datasets.json").exists()) return dir
            val appCandidate = File(dir, "app/src/main/assets/datasets/datasets.json")
            if (appCandidate.exists()) return File(dir, "app")
            dir = dir.parentFile ?: return@repeat
        }
        fail("Could not locate app dir from ${File(".").absolutePath}")
        error("unreachable")
    }

    private fun readCsvColumn(file: File, column: String): DoubleArray {
        file.bufferedReader().use { reader ->
            val header = reader.readLine().split(",")
            val idx = header.indexOf(column)
            require(idx >= 0) { "Column '$column' not found in ${file.name}: $header" }
            return reader.readLines().filter { it.isNotBlank() }
                .map { it.split(",")[idx].toDouble() }.toDoubleArray()
        }
    }

    private data class GoldenRow(
        val t: Double, val r: Double, val spo2: Double,
        val piIr: Double, val piRed: Double, val valid: Boolean, val reason: String
    )

    /** |acc| from the Float axes, exactly as the app / spo2_reference.py compute it. */
    private fun accMag(file: File): DoubleArray {
        val x = readCsvColumn(file, "accX"); val y = readCsvColumn(file, "accY"); val z = readCsvColumn(file, "accZ")
        return DoubleArray(x.size) {
            val a = x[it].toFloat().toDouble(); val b = y[it].toFloat().toDouble(); val c = z[it].toFloat().toDouble()
            Math.sqrt(a * a + b * b + c * c)
        }
    }

    private fun readGolden(file: File): List<GoldenRow> {
        fun d(s: String) = if (s.isBlank()) Double.NaN else s.toDouble()
        return file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split(",")
            GoldenRow(
                t = d(f[0]), r = d(f[1]), spo2 = d(f[2]), piIr = d(f[3]), piRed = d(f[4]),
                valid = f[5].trim().equals("True", ignoreCase = true),
                reason = f.getOrElse(6) { "" }.trim()
            )
        }
    }

    @Test
    fun `reproduces the Phase 1 golden table for the shipped 25Hz sep16 asset`() {
        val appDir = findAppDir()
        val datasetFile = File(appDir, "src/main/assets/datasets/hw_sep16.csv")
        val goldenFile = File(appDir, "src/test/resources/golden/spo2_sep16_25hz.csv")
        assertTrue("missing $datasetFile", datasetFile.exists())
        assertTrue("missing $goldenFile", goldenFile.exists())

        val ir = readCsvColumn(datasetFile, "ir")
        val red = readCsvColumn(datasetFile, "red")
        val golden = readGolden(goldenFile)

        val result = Spo2Engine.estimate(ir, red, 25.0, Spo2Config(), null, accMag(datasetFile))
        assertTrue("expected Available, got $result", result is Spo2Result.Available)
        val available = result as Spo2Result.Available

        assertEquals("window count", golden.size, available.windows.size)
        assertEquals("accepted count", golden.count { it.valid }, available.windows.count { it.valid })

        for (i in golden.indices) {
            val g = golden[i]
            val a = available.windows[i]
            assertEquals("t[$i]", g.t, a.tSec, 1e-6)
            if (!g.r.isNaN()) assertEquals("R[$i]", g.r, a.r, 1e-6) else assertTrue("R[$i] expected NaN", a.r.isNaN())
            if (!g.piIr.isNaN()) assertEquals("piIr[$i]", g.piIr, a.piIr, 1e-6) else assertTrue(a.piIr.isNaN())
            if (!g.piRed.isNaN()) assertEquals("piRed[$i]", g.piRed, a.piRed, 1e-6) else assertTrue(a.piRed.isNaN())
            if (!g.spo2.isNaN()) assertEquals("spo2[$i]", g.spo2, a.spo2, 1e-6) else assertTrue(a.spo2.isNaN())
            assertEquals("valid[$i]", g.valid, a.valid)
            assertEquals("reason[$i]", g.reason, a.rejectReason.name)
        }
    }

    @Test
    fun `one-point calibration to ref 98 matches the Python reference`() {
        val appDir = findAppDir()
        val datasetFile = File(appDir, "src/main/assets/datasets/hw_sep16.csv")
        val ir = readCsvColumn(datasetFile, "ir")
        val red = readCsvColumn(datasetFile, "red")

        val cfg = Spo2Config(singlePoint = Spo2SinglePointCalibration(enable = true, refSpo2 = 98.0))
        val result = Spo2Engine.estimate(ir, red, 25.0, cfg) as Spo2Result.Available

        // tools/spo2_reference.py on this same shipped asset: offset -2.79%,
        // 83/83 accepted, median 98.0%, range 96.9-99.1%.
        assertEquals(83, result.windows.size)
        assertEquals(83, result.summary.accepted)
        assertEquals(98.0, result.summary.medianAcceptedSpo2!!, 0.05)
        assertEquals(96.9, result.summary.minAcceptedSpo2!!, 0.1)
        assertEquals(99.1, result.summary.maxAcceptedSpo2!!, 0.1)
    }

    @Test
    fun `single wavelength is not available`() {
        val ir = DoubleArray(1000) { 90000.0 + it }
        val result = Spo2Engine.estimate(ir, null, 25.0)
        assertTrue(result is Spo2Result.NotAvailable)
    }

    @Test
    fun `recording shorter than warmup plus window is not available`() {
        val n = 200 // 8s at 25Hz - under even the shrink-to-fit floor (one 12s window)
        val ir = DoubleArray(n) { 90000.0 + it }
        val red = DoubleArray(n) { 50000.0 + it }
        val result = Spo2Engine.estimate(ir, red, 25.0)
        assertTrue(result is Spo2Result.NotAvailable)
    }

    @Test
    fun `non positive DC rejects every window`() {
        val n = 25 * 60
        val ir = DoubleArray(n) { -1000.0 }
        val red = DoubleArray(n) { 50000.0 }
        val result = Spo2Engine.estimate(ir, red, 25.0, Spo2Config(warmupSec = 0.0)) as Spo2Result.Available
        assertTrue(result.windows.isNotEmpty())
        assertTrue(result.windows.all { it.rejectReason == Spo2RejectReason.NON_POSITIVE_DC })
        assertEquals(0, result.summary.accepted)
    }

    @Test
    fun `flat signal has zero perfusion and is rejected`() {
        val n = 25 * 60
        val ir = DoubleArray(n) { 90000.0 }
        val red = DoubleArray(n) { 50000.0 }
        val result = Spo2Engine.estimate(ir, red, 25.0, Spo2Config(warmupSec = 0.0)) as Spo2Result.Available
        assertTrue(result.windows.isNotEmpty())
        assertTrue(result.windows.all { it.rejectReason == Spo2RejectReason.LOW_PERFUSION })
        assertEquals(0, result.summary.accepted)
    }

    // ---- v3 gates and aggregation ------------------------------------------------

    private val fs = 25.0

    /** 60 s of clean synthetic dual-wavelength PPG at [bpm], R = [r]. */
    private fun synth(bpm: Double = 72.0, r: Double = 0.6, n: Int = (60 * fs).toInt()): Pair<DoubleArray, DoubleArray> {
        val ir = DoubleArray(n) { 115_000.0 + 400 * Math.sin(2 * Math.PI * bpm / 60 * it / fs) }
        val red = DoubleArray(n) { 84_000.0 + 84_000.0 * r * (400.0 / 115_000.0) * Math.sin(2 * Math.PI * bpm / 60 * it / fs) }
        return ir to red
    }

    private val noWarmup = Spo2Config(warmupSec = 0.0)

    @Test
    fun `clean synthetic signal - every window valid, R recovered`() {
        val (ir, red) = synth(r = 0.6)
        val res = Spo2Engine.estimate(ir, red, fs, noWarmup) as Spo2Result.Available
        assertTrue(res.windows.all { it.valid })
        assertEquals(0.6, res.summary.medianR, 1e-3)
        assertEquals(110 - 25 * 0.6, res.summary.medianAcceptedSpo2!!, 0.05)
    }

    @Test
    fun `dropout-flagged windows are rejected and leave a gap`() {
        val (ir, red) = synth()
        val flags = IntArray(ir.size)
        for (i in 700 until 710) flags[i] = com.eee312.ppgmonitor.dsp.SampleFlags.FILLED
        val res = Spo2Engine.estimate(ir, red, fs, noWarmup, flags) as Spo2Result.Available
        val dropped = res.windows.filter { it.rejectReason == Spo2RejectReason.DROPOUT }
        assertEquals(6, dropped.size) // 12 s windows every 2 s touching samples 700-709
        assertTrue(dropped.all { it.spo2.isNaN() })
    }

    @Test
    fun `uncorrelated red is rejected`() {
        val (ir, _) = synth()
        val rnd = java.util.Random(1)
        val red = DoubleArray(ir.size) { 84_000.0 + 150 * rnd.nextGaussian() }
        val res = Spo2Engine.estimate(ir, red, fs, noWarmup) as Spo2Result.Available
        assertTrue(res.windows.all { it.rejectReason == Spo2RejectReason.LOW_CORRELATION })
    }

    @Test
    fun `motion windows are rejected, in any accelerometer unit`() {
        val (ir, red) = synth()
        for (g in listOf(9.81, 1.0)) { // m/s^2 or g
            val acc = DoubleArray(ir.size) { i -> g + if (i in 500 until 800) 0.3 * g * Math.sin(2 * Math.PI * 1.8 * i / fs) else 0.0 }
            val res = Spo2Engine.estimate(ir, red, fs, noWarmup, null, acc) as Spo2Result.Available
            assertTrue(res.windows.any { it.rejectReason == Spo2RejectReason.MOTION })
            assertTrue(res.windows.first().valid && res.windows.last().valid)
        }
    }

    @Test
    fun `too few beats is rejected`() {
        val (ir, red) = synth(bpm = 72.0) // 14.4 beats per 12 s window
        val res = Spo2Engine.estimate(ir, red, fs, noWarmup.copy(minBeats = 16)) as Spo2Result.Available
        assertTrue(res.windows.all { it.rejectReason == Spo2RejectReason.TOO_FEW_BEATS })
    }

    @Test
    fun `summary is N-A with fewer than 3 valid windows`() {
        val (ir, red) = synth()
        val flags = IntArray(ir.size) { com.eee312.ppgmonitor.dsp.SampleFlags.FILLED }
        for (i in 0 until 350) flags[i] = 0 // exactly 2 clean windows (0-12 s, 2-14 s)
        val res = Spo2Engine.estimate(ir, red, fs, noWarmup, flags) as Spo2Result.Available
        assertEquals(2, res.summary.accepted)
        assertEquals(null, res.summary.medianAcceptedSpo2)
    }

    @Test
    fun `above 100 is rejected but kept with its raw value, and counted`() {
        val (ir, red) = synth(r = 0.3)
        val res = Spo2Engine.estimate(ir, red, fs, noWarmup.copy(validRRange = 0.2 to 3.0)) as Spo2Result.Available
        assertTrue(res.windows.all { it.rejectReason == Spo2RejectReason.ABOVE_100 && it.spo2 > 100.0 })
        assertEquals(res.windows.size, res.summary.above100Count)
        assertEquals(102.5, res.summary.maxRawSpo2, 0.05)
    }

    @Test
    fun `swapped red and IR is called out`() {
        val (ir, red) = synth(r = 0.6)
        val res = Spo2Engine.estimate(red, ir, fs, noWarmup) as Spo2Result.Available // swapped
        assertTrue(res.summary.medianR > 1.5)
        assertTrue(res.summary.diagnosis.contains("swapped"))
    }

    @Test
    fun `finger off and saturation gates for the live rig`() {
        val (ir, red) = synth()
        val cfg = noWarmup.copy(fingerPresentDc = 50_000.0, saturationCounts = 262_000.0)
        val off = Spo2Engine.estimate(DoubleArray(ir.size) { ir[it] - 100_000 }, red, fs, cfg) as Spo2Result.Available
        assertTrue(off.windows.all { it.rejectReason == Spo2RejectReason.FINGER_OFF })
        val sat = Spo2Engine.estimate(DoubleArray(ir.size) { 262_143.0 }, red, fs, cfg) as Spo2Result.Available
        assertTrue(sat.windows.all { it.rejectReason == Spo2RejectReason.SATURATED })
    }

    @Test
    fun `other sample rates work (filters designed for the real rate)`() {
        for (rate in listOf(20.0, 24.6, 30.0)) {
            val n = (60 * rate).toInt()
            val ir = DoubleArray(n) { 115_000.0 + 400 * Math.sin(2 * Math.PI * 1.2 * it / rate) }
            val red = DoubleArray(n) { 84_000.0 + 175 * Math.sin(2 * Math.PI * 1.2 * it / rate) }
            val res = Spo2Engine.estimate(ir, red, rate, noWarmup) as Spo2Result.Available
            assertTrue("rate $rate", res.windows.all { it.valid })
        }
    }
}
