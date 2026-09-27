package com.eee312.ppgmonitor.sensor.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TableReadersTest {

    private fun rows(r: TableReader): List<List<String>> {
        val out = ArrayList<List<String>>()
        while (true) out += r.nextRow() ?: break
        return out
    }

    @Test
    fun `csv - comma, header, blank lines, BOM`() {
        val r = CsvTableReader(StringReader("﻿time,ir,red\n\n0.0,115000,84000\n\n0.04,115010,84001\n"))
        assertEquals(listOf("time", "ir", "red"), r.header)
        assertEquals(listOf(listOf("0.0", "115000", "84000"), listOf("0.04", "115010", "84001")), rows(r))
    }

    @Test
    fun `csv - semicolon with decimal commas and quotes`() {
        val r = CsvTableReader(StringReader("\"Time (s)\";\"IR; raw\";Red\n0,04;115000;84000\n\"0,08\";\"115 010\";\n"))
        assertEquals(listOf("Time (s)", "IR; raw", "Red"), r.header)
        val all = rows(r)
        assertEquals(listOf("0,04", "115000", "84000"), all[0])
        assertEquals(listOf("0,08", "115 010", ""), all[1])
        assertEquals(0.04, Cells.number("0,04"), 0.0)
    }

    @Test
    fun `csv - tab separated, quoted field with delimiter and escaped quote`() {
        val r = CsvTableReader(StringReader("a\tb\tc\n1\t\"x\ty \"\"q\"\"\"\t3\n"))
        assertEquals(listOf("a", "b", "c"), r.header)
        assertEquals(listOf("1", "x\ty \"q\"", "3"), rows(r)[0])
    }

    @Test
    fun `csv - no header row gives generated names and keeps the first row`() {
        val r = CsvTableReader(StringReader("115684,84143,-8.138,-1.815,5.818\n115700,84150,-8.1,-1.8,5.8\n"))
        assertEquals(listOf("column 1", "column 2", "column 3", "column 4", "column 5"), r.header)
        assertEquals(2, rows(r).size)
    }

    @Test
    fun `csv - short rows are padded, NaN and blanks are missing`() {
        val r = CsvTableReader(StringReader("time,ir,red\n0,1,2\n0.04,NaN\n0.08,,3\n"))
        val all = rows(r)
        assertEquals(listOf("0.04", "NaN", ""), all[1])
        assertTrue(Cells.number(all[1][1]).isNaN())
        assertTrue(Cells.number(all[2][1]).isNaN())
    }

    // ---- XLSX, fixture built here ------------------------------------------------

    private fun xlsx(sheetXml: String, shared: List<String>): File {
        val f = File.createTempFile("fixture", ".xlsx")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId3"/><sheet name="Other" sheetId="2" r:id="rId1"/></sheets></workbook>"""
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId3" Type="worksheet" Target="worksheets/sheet2.xml"/></Relationships>"""
            )
            put(
                "xl/sharedStrings.xml",
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    shared.joinToString("") { "<si><r><t>${it.take(2)}</t></r><r><t>${it.drop(2)}</t></r></si>" } + "</sst>"
            )
            put("xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>WRONG SHEET</t></is></c></row></sheetData></worksheet>")
            put("xl/worksheets/sheet2.xml", sheetXml)
        }
        return f
    }

    @Test
    fun `xlsx - first sheet via workbook, shared and inline strings, numbers, gaps by reference`() {
        val sheet = """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>red</t></is></c><c r="D1" t="s"><v>2</v></c></row>
<row r="2"><c r="A2"><v>0</v></c><c r="B2"><v>115000</v></c><c r="C2"><v>84000</v></c><c r="D2"><v>-8.138</v></c></row>
<row r="4"><c r="A4"><v>0.04</v></c><c r="C4"><v>84010</v></c></row>
<row r="5"><c r="A5" t="n"><v>0.08</v></c><c r="B5"><v>115020</v></c><c r="D5" t="str"><v>nan</v></c></row>
</sheetData></worksheet>"""
        val f = xlsx(sheet, listOf("time", "ir", "accX"))
        XlsxTableReader(f) { KXmlParser() }.use { r ->
            assertEquals(listOf("time", "ir", "red", "accX"), r.header)
            val all = rows(r)
            assertEquals(3, all.size) // empty row 3 is absent in the XML
            assertEquals(listOf("0", "115000", "84000", "-8.138"), all[0])
            assertEquals(listOf("0.04", "", "84010", ""), all[1]) // B4 missing -> ""
            assertEquals(listOf("0.08", "115020", "", "nan"), all[2])
        }
    }

    @Test
    fun `xlsx - not a workbook gives a clear error`() {
        val f = File.createTempFile("bad", ".xlsx")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write(1); z.closeEntry() }
        try {
            XlsxTableReader(f) { KXmlParser() }
            fail("expected TableFormatException")
        } catch (e: TableFormatException) {
            assertTrue(e.message!!.contains("workbook"))
        }
    }

    @Test
    fun `column letters`() {
        assertEquals(0, XlsxTableReader.columnIndex("A1"))
        assertEquals(25, XlsxTableReader.columnIndex("Z9"))
        assertEquals(26, XlsxTableReader.columnIndex("AA10"))
    }

    @Test
    fun `plan - no time column asks for the rate`() {
        val header = listOf("ir", "red", "accX", "accY", "accZ")
        val prefix = List(100) { listOf("115000", "84000", "1", "2", "3") }
        val r = SignalFileAnalyzer.plan(header, prefix)
        assertTrue(r is PlanResult.NeedRate)
        val ready = SignalFileAnalyzer.plan(header, prefix, null, 25.0) as PlanResult.Ready
        assertEquals(25.0, ready.plan.playbackRateHz, 0.0)
        assertNull(ready.plan.mapping.time)
    }

    @Test
    fun `plan - unknown columns ask for a mapping`() {
        val r = SignalFileAnalyzer.plan(listOf("a", "b"), List(10) { listOf("1", "2") })
        assertTrue(r is PlanResult.NeedMapping)
    }

    @Test
    fun `plan - milliseconds time column`() {
        val header = listOf("t_ms", "ir")
        val prefix = List(100) { listOf((it * 40).toString(), "115000") }
        val p = (SignalFileAnalyzer.plan(header, prefix) as PlanResult.Ready).plan
        assertEquals(25.0, p.fileRateHz, 1e-9)
        assertEquals(TimeUnit.MILLISECONDS, p.mapping.timeUnit)
        val header2 = listOf("time", "ir") // unit not in the name, steps of 40 -> ms
        val p2 = (SignalFileAnalyzer.plan(header2, prefix) as PlanResult.Ready).plan
        assertEquals(25.0, p2.fileRateHz, 1e-9)
    }
}
