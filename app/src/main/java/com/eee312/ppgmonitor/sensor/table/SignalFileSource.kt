package com.eee312.ppgmonitor.sensor.table

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import java.io.File
import java.io.IOException

/**
 * Opens a CSV / Excel file picked through the Storage Access Framework.
 * The type is decided by content, not by the (often wrong) MIME type:
 *   "PK\u0003\u0004"  -> .xlsx (copied to the app's cache so the zip can be
 *                       read with random access)
 *   D0 CF 11 E0       -> old .xls: refused with a clear message
 *   anything else     -> CSV / text
 */
class SignalFileSource private constructor(
    val displayName: String,
    private val open: () -> TableReader
) {
    fun openTable(): TableReader = open()

    companion object {
        private val XLS_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** Blocking (call off the main thread). Throws TableFormatException
         * with a user-facing message for anything it can't read. */
        fun open(context: Context, uri: Uri): SignalFileSource {
            val cr = context.contentResolver
            val name = displayName(context, uri)
            val head = ByteArray(8)
            val n = try {
                cr.openInputStream(uri)?.use { it.read(head) } ?: throw IOException("no stream")
            } catch (e: Exception) {
                throw TableFormatException("Could not open \"$name\": ${e.message}")
            }
            if (n <= 0) throw TableFormatException("\"$name\" is empty.")
            if (startsWith(head, XLS_MAGIC) || name.endsWith(".xls", ignoreCase = true)) {
                throw TableFormatException("\"$name\" is an old Excel .xls file. Please save the file as .xlsx or .csv.")
            }
            if (startsWith(head, ZIP_MAGIC)) {
                val cached = File(context.cacheDir, "upload.xlsx")
                cr.openInputStream(uri)?.use { input -> cached.outputStream().use { input.copyTo(it) } }
                    ?: throw TableFormatException("Could not read \"$name\".")
                return SignalFileSource(name) { XlsxTableReader(cached) { Xml.newPullParser() } }
            }
            return SignalFileSource(name) {
                val stream = cr.openInputStream(uri) ?: throw TableFormatException("Could not read \"$name\".")
                CsvTableReader(stream.bufferedReader(Charsets.UTF_8))
            }
        }

        private fun startsWith(b: ByteArray, magic: ByteArray) = magic.indices.all { b[it] == magic[it] }

        private fun displayName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            } catch (_: Exception) {}
            return uri.lastPathSegment ?: "file"
        }
    }
}
