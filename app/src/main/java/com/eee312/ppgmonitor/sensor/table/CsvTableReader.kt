package com.eee312.ppgmonitor.sensor.table

import java.io.BufferedReader
import java.io.Reader

/**
 * Streaming CSV reader: detects the delimiter (',' ';' or tab) from the
 * first lines, handles quoted fields ("a,b", doubled "" quotes, quoted
 * line breaks), skips blank lines, strips a UTF-8 BOM, and detects
 * whether the first row is a header.
 */
class CsvTableReader(reader: Reader) : TableReader {

    private val input: BufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader, 1 shl 16)
    private val lookahead = ArrayDeque<String>()
    private val delimiter: Char
    override val header: List<String>
    private var width = 0
    private var pendingFirstRow: List<String>? = null

    init {
        // Peek at up to 20 non-blank physical lines to pick the delimiter.
        val sample = ArrayList<String>()
        while (sample.size < 20) {
            val line = input.readLine() ?: break
            val l = if (lookahead.isEmpty() && sample.isEmpty()) line.removePrefix("﻿") else line
            lookahead.addLast(l)
            if (l.isNotBlank()) sample += l
        }
        if (sample.isEmpty()) throw TableFormatException("The file is empty.")
        delimiter = pickDelimiter(sample)
        val first = readRecord() ?: throw TableFormatException("The file is empty.")
        if (HeaderDetect.looksLikeHeader(first)) {
            header = first.map { it.trim() }
        } else {
            header = HeaderDetect.generatedHeader(first.size)
            pendingFirstRow = first
        }
        width = header.size
    }

    private fun pickDelimiter(lines: List<String>): Char {
        val candidates = charArrayOf(',', ';', '\t')
        var best = ','
        var bestScore = -1
        for (c in candidates) {
            val counts = lines.map { countOutsideQuotes(it, c) }
            val mode = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
            if (mode == 0) continue
            val consistent = counts.count { it == mode }
            val score = consistent * 100 + mode
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, c: Char): Int {
        var inQ = false
        var n = 0
        for (ch in line) {
            if (ch == '"') inQ = !inQ else if (ch == c && !inQ) n++
        }
        return n
    }

    private fun nextPhysicalLine(): String? = if (lookahead.isNotEmpty()) lookahead.removeFirst() else input.readLine()

    /** One logical record (may span lines inside quotes); null at EOF.
     * Blank lines are skipped. */
    private fun readRecord(): List<String>? {
        var line: String
        while (true) {
            line = nextPhysicalLine() ?: return null
            if (line.isNotBlank()) break
        }
        val fields = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (true) {
            while (i < line.length) {
                val ch = line[i]
                if (inQ) {
                    if (ch == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                    } else sb.append(ch)
                } else {
                    when (ch) {
                        '"' -> inQ = true
                        delimiter -> { fields += sb.toString(); sb.setLength(0) }
                        else -> sb.append(ch)
                    }
                }
                i++
            }
            if (!inQ) break
            val next = nextPhysicalLine() ?: break // unterminated quote at EOF: take what we have
            sb.append('\n')
            line = next
            i = 0
        }
        fields += sb.toString()
        return fields
    }

    override fun nextRow(): List<String>? {
        pendingFirstRow?.let { pendingFirstRow = null; return fit(it) }
        val r = readRecord() ?: return null
        return fit(r)
    }

    private fun fit(r: List<String>): List<String> = when {
        r.size == width -> r
        r.size > width -> r.subList(0, width)
        else -> r + List(width - r.size) { "" }
    }

    override fun close() = input.close()
}
