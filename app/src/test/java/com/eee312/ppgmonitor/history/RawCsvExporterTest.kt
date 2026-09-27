package com.eee312.ppgmonitor.history

import com.eee312.ppgmonitor.dsp.SampleFlags
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.util.Locale

class RawCsvExporterTest {

    private fun row(
        id: Long, tSec: Double, tSrc: Double? = tSec, ir: Int? = 115000, red: Int? = 84000, green: Int? = null,
        acc: Triple<Double?, Double?, Double?> = Triple(-8.138f.toDouble(), -1.815f.toDouble(), 5.818f.toDouble()),
        flags: Int? = 0, rawPpg: Double = (ir ?: 0).toDouble()
    ) = RawExportRow(id, tSec, tSrc, rawPpg, ir, red, green, acc.first, acc.second, acc.third, flags)

    @Test
    fun `canonical layout, values as received, inserted slots skipped`() {
        val w = StringWriter()
        w.write(RawCsvExporter.header(includeGreen = false))
        val n = RawCsvExporter.writeRows(
            w,
            listOf(
                row(1, 0.0, 0.0),
                row(2, 0.04, 0.041, acc = Triple(null, null, null)),               // "nan" axes -> empty
                row(3, 0.08, null, ir = null, red = null, flags = SampleFlags.INSERTED), // gap slot -> not written
                row(4, 0.12, 0.121, flags = SampleFlags.FILLED, rawPpg = 99999.0), // filled: export the RECEIVED ir
                row(5, 0.16, 0.16, red = null, acc = Triple(0.0001f.toDouble(), 0.0, 9.81f.toDouble()))
            ),
            includeGreen = false
        )
        assertEquals(4, n)
        assertEquals(
            "time,ir,red,accX,accY,accZ\n" +
                "0.000,115000,84000,-8.138,-1.815,5.818\n" +
                "0.041,115000,84000,,,\n" +
                "0.121,115000,84000,-8.138,-1.815,5.818\n" +
                "0.160,115000,,0.0001,0,9.81\n",
            w.toString()
        )
    }

    @Test
    fun `legacy readings export rawPpg as ir and leave missing columns empty`() {
        val w = StringWriter()
        RawCsvExporter.writeRows(
            w, listOf(RawExportRow(1, 1.5, null, 112154.0, null, null, null, null, null, null, null)), false
        )
        assertEquals("1.500,112154,,,,\n", w.toString())
    }

    @Test
    fun `decimal point stays a dot in any locale, green column when present`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val w = StringWriter()
            w.write(RawCsvExporter.header(includeGreen = true))
            RawCsvExporter.writeRows(w, listOf(row(1, 2.5, 2.5, green = 12)), includeGreen = true)
            assertEquals("time,ir,red,accX,accY,accZ,green\n2.500,115000,84000,-8.138,-1.815,5.818,12\n", w.toString())
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun `file name`() {
        val t = java.util.Calendar.getInstance().apply { set(2026, 8, 28, 14, 32, 5) }.timeInMillis
        assertEquals("Reading_12_20260928_1432_raw.csv", RawCsvExporter.fileName("Reading 12", t))
    }

    /** Writes a real-size sample export (hw_sep16 as if recorded) into
     * build/ so it can be checked with MATLAB's load_dataset.m. */
    @Test
    fun `writes an hw_sep16 export for the MATLAB check`() {
        var dir = File(".").absoluteFile
        while (!File(dir, "src/main/assets/datasets").exists() && dir.parentFile != null) {
            dir = if (File(dir, "app/src/main/assets/datasets").exists()) File(dir, "app") else dir.parentFile
        }
        val src = File(dir, "src/main/assets/datasets/hw_sep16.csv")
        val rows = src.readLines().drop(1).mapIndexed { i, line ->
            val f = line.split(',')
            RawExportRow(
                i + 1L, i / 25.0, f[0].toDouble(), f[1].toDouble(), f[1].toInt(), f[2].toInt(), f[6].toInt(),
                f[3].toFloat().toDouble(), f[4].toFloat().toDouble(), f[5].toFloat().toDouble(), 0
            )
        }
        val out = File(dir, "build/export-check/Reading_0_hw_sep16_raw.csv")
        out.parentFile.mkdirs()
        out.bufferedWriter().use { w ->
            w.write(RawCsvExporter.header(true))
            RawCsvExporter.writeRows(w, rows, true)
        }
        assertEquals(rows.size + 1, out.readLines().size)
    }
}
