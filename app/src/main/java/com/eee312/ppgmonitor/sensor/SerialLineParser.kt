package com.eee312.ppgmonitor.sensor

import com.eee312.ppgmonitor.model.SensorSample

/**
 * Parses one line of the ESP32 USB-serial stream (PpgStream.ino, see
 * USB_SERIAL_CONTRACT.md). Pure Kotlin - no Android types - so it is
 * unit-tested with lines formatted exactly like the firmware's.
 *
 * Accepted frames (comma-separated ASCII, one per line):
 *   5 fields  ir,red,accX,accY,accZ                  (old firmware)
 *   6 fields  ir,red,accX,accY,accZ,green            (old firmware, SEND_GREEN 1)
 *   7 fields  ir,red,accX,accY,accZ,green,seq        (PpgStream.ino v1.1+)
 *             green is empty when SEND_GREEN is 0; seq is the firmware's
 *             record counter (increments by 1 per FIFO record).
 * ir/red/green: integer ADC counts. accX/Y/Z: m/s^2, or "nan" when the
 * MPU6050 read failed for that sample (the PPG values are still real).
 * Lines starting with '#' are the firmware's own messages.
 */
object SerialLineParser {

    sealed class Result {
        data class Sample(val sample: SensorSample) : Result()
        data class Comment(val text: String) : Result()
        data class Malformed(val reason: String) : Result()
    }

    fun parse(line: String, timestampMs: Long, arrivalSec: Double?): Result {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return Result.Comment(trimmed)
        val f = trimmed.split(',')
        if (f.size !in 5..7) return Result.Malformed("expected 5, 6 or 7 fields, got ${f.size}")
        return try {
            val ir = f[0].trim().toInt()
            val redField = f[1].trim()
            val red = if (redField.isEmpty()) null else redField.toInt()
            val green = if (f.size >= 6) f[5].trim().takeIf { it.isNotEmpty() }?.toIntOrNull() else null
            val seq = if (f.size == 7) f[6].trim().toLong() else null
            Result.Sample(
                SensorSample(
                    timestampMs = timestampMs,
                    ir = ir,
                    red = red,
                    accX = axis(f[2]),
                    accY = axis(f[3]),
                    accZ = axis(f[4]),
                    green = green,
                    sourceTimeSec = arrivalSec,
                    seq = seq
                )
            )
        } catch (e: NumberFormatException) {
            Result.Malformed("non-numeric field")
        }
    }

    /** "nan" (any case) or an empty field -> NaN: axis unreadable. */
    private fun axis(field: String): Float {
        val t = field.trim()
        if (t.isEmpty() || t.equals("nan", ignoreCase = true)) return Float.NaN
        return t.toFloat()
    }
}
