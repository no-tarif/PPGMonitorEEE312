package com.eee312.ppgmonitor.sensor.table

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Reads the FIRST worksheet of an .xlsx file without Apache POI: the file
 * is a zip; xl/workbook.xml names the sheets in order, its relationship
 * file maps the first sheet to a part (usually xl/worksheets/sheet1.xml),
 * xl/sharedStrings.xml holds the text cells. The sheet is streamed with
 * an XmlPullParser, one <row> at a time.
 *
 * Cells: shared strings (t="s"), inline strings (t="inlineStr"), plain
 * strings (t="str"), booleans, and numbers (no t, or t="n"). Empty cells
 * are simply absent from the XML - each cell's r="C12" reference places it
 * in the right column, so gaps become "".
 *
 * [newParser] creates a pull parser: android.util.Xml.newPullParser() on
 * the phone, kxml2 in JVM tests.
 */
class XlsxTableReader(
    private val file: File,
    private val newParser: () -> XmlPullParser
) : TableReader {

    private val zip = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw TableFormatException("This .xlsx file could not be opened (${e.message}).")
    }
    private val sharedStrings: List<String>
    private val sheetStream: InputStream
    private val parser: XmlPullParser
    override val header: List<String>
    private var width = 0
    private var pendingFirstRow: List<String>? = null
    private var done = false

    init {
        val sheetPath = firstSheetPath()
        sharedStrings = readSharedStrings()
        val entry = zip.getEntry(sheetPath) ?: throw TableFormatException("The workbook has no readable first sheet.")
        sheetStream = zip.getInputStream(entry)
        parser = newParser().apply { setInput(sheetStream, "UTF-8") }
        var first: List<String>?
        do { first = readRow() } while (first != null && first.all { it.isBlank() })
        if (first == null) throw TableFormatException("The first worksheet is empty.")
        if (HeaderDetect.looksLikeHeader(first)) {
            header = trimTrailingEmpty(first).map { it.trim() }
        } else {
            header = HeaderDetect.generatedHeader(trimTrailingEmpty(first).size)
            pendingFirstRow = first
        }
        width = header.size
    }

    private fun trimTrailingEmpty(r: List<String>): List<String> {
        var n = r.size
        while (n > 0 && r[n - 1].isBlank()) n--
        return r.subList(0, n)
    }

    private fun firstSheetPath(): String {
        val wb = zip.getEntry("xl/workbook.xml") ?: throw TableFormatException("Not an Excel workbook (no xl/workbook.xml).")
        var firstRid: String? = null
        zip.getInputStream(wb).use { s ->
            val p = newParser().apply { setInput(s, "UTF-8") }
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.localName() == "sheet") {
                    firstRid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith("id") && p.getAttributeName(it) != "sheetId" }
                        ?.let { p.getAttributeValue(it) }
                    break
                }
            }
        }
        val rels = zip.getEntry("xl/_rels/workbook.xml.rels")
        if (firstRid != null && rels != null) {
            zip.getInputStream(rels).use { s ->
                val p = newParser().apply { setInput(s, "UTF-8") }
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.localName() == "Relationship" &&
                        p.getAttributeValue(null, "Id") == firstRid
                    ) {
                        val target = p.getAttributeValue(null, "Target") ?: break
                        return if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
                    }
                }
            }
        }
        return "xl/worksheets/sheet1.xml"
    }

    private fun readSharedStrings(): List<String> {
        val e = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        zip.getInputStream(e).use { s ->
            val p = newParser().apply { setInput(s, "UTF-8") }
            val sb = StringBuilder()
            var inSi = false
            var inT = false
            var inRph = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.localName()) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> inT = inSi
                        "rPh" -> inRph = true // phonetic run: not part of the text
                    }
                    XmlPullParser.TEXT -> if (inT && !inRph) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.localName()) {
                        "si" -> { out += sb.toString(); inSi = false }
                        "t" -> inT = false
                        "rPh" -> inRph = false
                    }
                }
            }
        }
        return out
    }

    /** Next <row> as cells, placed by column reference; null at the end. */
    private fun readRow(): List<String>? {
        if (done) return null
        // advance to <row>
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) { done = true; return null }
            if (ev == XmlPullParser.START_TAG && parser.localName() == "row") break
        }
        val cells = ArrayList<String>()
        var nextCol = 0
        var cellType: String? = null
        var cellCol = 0
        val value = StringBuilder()
        var capture = false
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) { done = true; break }
            if (ev == XmlPullParser.START_TAG) {
                when (parser.localName()) {
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        cellCol = parser.getAttributeValue(null, "r")?.let { columnIndex(it) } ?: nextCol
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (capture) value.append(parser.text)
            } else if (ev == XmlPullParser.END_TAG) {
                when (parser.localName()) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val text = when (cellType) {
                            "s" -> value.toString().trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                            "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                            else -> value.toString()
                        }
                        while (cells.size < cellCol) cells += ""
                        if (cells.size == cellCol) cells += text else cells[cellCol] = text
                        nextCol = cellCol + 1
                    }
                    "row" -> break
                }
            }
        }
        return cells
    }

    override fun nextRow(): List<String>? {
        pendingFirstRow?.let { pendingFirstRow = null; return fit(it) }
        while (true) {
            val r = readRow() ?: return null
            if (r.all { it.isBlank() }) continue
            return fit(r)
        }
    }

    private fun fit(r: List<String>): List<String> = when {
        r.size == width -> r
        r.size > width -> r.subList(0, width)
        else -> r + List(width - r.size) { "" }
    }

    override fun close() {
        try { sheetStream.close() } catch (_: Exception) {}
        zip.close()
    }

    /** Tag name without a namespace prefix ("x:row" -> "row"): some writers
     * prefix the SpreadsheetML elements, and the parsers run namespace-unaware. */
    private fun XmlPullParser.localName(): String = name?.substringAfter(':') ?: ""

    companion object {
        /** "C12" -> 2 (0-based column). */
        fun columnIndex(ref: String): Int {
            var n = 0
            for (ch in ref) {
                if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
                else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
                else break
            }
            return n - 1
        }
    }
}
