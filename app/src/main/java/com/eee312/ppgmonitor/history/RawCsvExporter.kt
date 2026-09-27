package com.eee312.ppgmonitor.history

import com.eee312.ppgmonitor.dsp.SampleFlags
import java.io.Writer
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The raw columns of one stored sample, as read for Export. */
data class RawExportRow(
    val id: Long,
    val tSec: Double,
    val tSrc: Double?,
    val rawPpg: Double,
    val irRaw: Int?,
    val redRaw: Int?,
    val greenRaw: Int?,
    val accX: Double?,
    val accY: Double?,
    val accZ: Double?,
    val flags: Int?
)

/**
 * Writes a reading's raw samples - what the ESP32 / dataset / file
 * actually delivered, before any dropout filling - in the project's
 * canonical capture layout (same as walk_clean.csv, class_1_clean.csv and
 * assets/datasets/hw_*.csv):
 *
 *     time,ir,red,accX,accY,accZ[,green]
 *
 * - time: seconds from the first sample, 3 decimals. The source's own time
 *   (file time column, BLE device clock, USB arrival time) when it was
 *   recorded (v3+), otherwise sample index / rate.
 * - ir, red, green: integer ADC counts; accX/Y/Z in the source's unit
 *   (m/s^2 for the rig), printed as received (no float noise).
 * - green: only present when the reading has green data.
 * - Missing values (a channel the source never had, an unreadable
 *   accelerometer sample, pre-v3 readings without per-axis data) are
 *   empty cells.
 * - Slots the dropout filler inserted because nothing arrived are NOT
 *   written - the file is what was received.
 * '.' decimal point and ',' separator regardless of the phone's locale,
 * so it opens in Excel and in MATLAB's load_dataset.m unchanged.
 */
object RawCsvExporter {

    fun header(includeGreen: Boolean): String =
        if (includeGreen) "time,ir,red,accX,accY,accZ,green\n" else "time,ir,red,accX,accY,accZ\n"

    /** Writes the rows it is given (call repeatedly for chunks); returns
     * how many were written. */
    fun writeRows(w: Writer, rows: List<RawExportRow>, includeGreen: Boolean): Int {
        var written = 0
        val sb = StringBuilder(64)
        for (r in rows) {
            if (SampleFlags.has(r.flags, SampleFlags.INSERTED)) continue
            sb.setLength(0)
            sb.append(String.format(Locale.ROOT, "%.3f", r.tSrc ?: r.tSec)).append(',')
            // Pre-v2 samples have no irRaw; rawPpg was always sample.ir then.
            val ir = r.irRaw ?: if (r.flags == null) Math.round(r.rawPpg).toInt() else null
            ir?.let { sb.append(it) }
            sb.append(',')
            r.redRaw?.let { sb.append(it) }
            sb.append(',').append(axis(r.accX))
            sb.append(',').append(axis(r.accY))
            sb.append(',').append(axis(r.accZ))
            if (includeGreen) {
                sb.append(',')
                r.greenRaw?.let { sb.append(it) }
            }
            sb.append('\n')
            w.write(sb.toString())
            written++
        }
        return written
    }

    /** Axes were received as Float and stored as Double; print the Float's
     * shortest decimal form ("-8.138", not "-8.13800048828125" or "1.0E-4"). */
    private fun axis(v: Double?): String =
        if (v == null || !v.isFinite()) "" else BigDecimal(v.toFloat().toString()).stripTrailingZeros().toPlainString()

    /** e.g. "Reading_12_20260928_1432_raw.csv". */
    fun fileName(readingName: String, startedAtEpochMs: Long): String {
        val safe = readingName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(startedAtEpochMs))
        return "${safe}_${stamp}_raw.csv"
    }
}

/** Streams a reading's raw CSV to [uri] (SAF CreateDocument result) in
 * id-keyed chunks on the IO dispatcher - never the whole reading in
 * memory, never on the main thread. Returns the number of rows written. */
object ReadingExport {
    private const val CHUNK = 5_000

    suspend fun writeRawCsv(context: android.content.Context, reading: ReadingEntity, uri: android.net.Uri): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dao = AppDatabase.get(context).sampleDao()
            val includeGreen = dao.hasGreen(reading.id)
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Could not open the chosen file for writing")
            var rows = 0
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(RawCsvExporter.header(includeGreen))
                var afterId = 0L
                while (true) {
                    val chunk = dao.getRawChunk(reading.id, afterId, CHUNK)
                    if (chunk.isEmpty()) break
                    rows += RawCsvExporter.writeRows(w, chunk, includeGreen)
                    afterId = chunk.last().id
                }
            }
            rows
        }
}
