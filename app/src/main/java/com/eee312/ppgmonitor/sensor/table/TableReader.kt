package com.eee312.ppgmonitor.sensor.table

import java.io.Closeable

/**
 * A spreadsheet-like table read one row at a time (never the whole file
 * as strings - a PhysioNet original is ~40 MB / 243k rows).
 * [header] is the column names (generated as "column 1".. when the file
 * has no header row). [nextRow] returns the cells of the next non-blank
 * row, padded/truncated to the header width, or null at the end.
 */
interface TableReader : Closeable {
    val header: List<String>
    fun nextRow(): List<String>?
}

/** Thrown for a file the app can't read; [message] is shown to the user. */
class TableFormatException(message: String) : Exception(message)

/** Header detection shared by CSV and XLSX: a first row counts as a header
 * when most of its non-empty cells are not numbers. */
internal object HeaderDetect {
    fun isNumeric(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.equals("nan", true)) return true
        return t.toDoubleOrNull() != null
    }

    fun looksLikeHeader(cells: List<String>): Boolean {
        val nonEmpty = cells.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return false
        val text = nonEmpty.count { !isNumeric(it) && !looksLikeDateTime(it) }
        return text * 2 > nonEmpty.size
    }

    fun looksLikeDateTime(s: String): Boolean =
        Regex("""^\d{4}-\d{2}-\d{2}[ T]\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?$""").matches(s.trim())

    fun generatedHeader(n: Int): List<String> = (1..n).map { "column $it" }
}
