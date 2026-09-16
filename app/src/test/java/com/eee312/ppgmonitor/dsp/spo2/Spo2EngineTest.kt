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
        val piIr: Double, val piRed: Double, val valid: Boolean
    )

    private fun readGolden(file: File): List<GoldenRow> {
        fun d(s: String) = if (s.isBlank()) Double.NaN else s.toDouble()
        return file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split(",")
            GoldenRow(
                t = d(f[0]), r = d(f[1]), spo2 = d(f[2]), piIr = d(f[3]), piRed = d(f[4]),
                valid = f[5].trim().equals("True", ignoreCase = true)
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

        val result = Spo2Engine.estimate(ir, red, 25.0, Spo2Config())
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
}
