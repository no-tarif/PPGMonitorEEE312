package com.eee312.ppgmonitor.sensor

import com.eee312.ppgmonitor.model.SensorSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 18-byte BLE notification BleSensorSource expects (Nordic UART
 * Service TX characteristic, little-endian) - see
 * docs/ESP32_LINK_GUIDE.md Q3:
 *   0-3   uint32 ESP32 millis()          -> sourceTimeSec (was discarded before v3)
 *   4-7   int32  ir
 *   8-11  int32  red
 *   12-17 int16  accX/Y/Z x 1000 (m/s^2)
 *
 * NOTE: HARDWARE_SETUP_GUIDE.md ships NO BLE firmware - the rig it builds
 * streams over USB only. This contract is kept (and tested) as written,
 * but has a known limit: int16 x 1000 only reaches +/-32.767 m/s^2
 * (3.3 g), while the MPU6050 is set to +/-8 g, so hard running could wrap
 * around. A future BLE sketch should use x 100 or int32 - change this
 * parser at the same time.
 */
object BlePacketParser {
    const val PACKET_SIZE_BYTES = 18

    fun parse(bytes: ByteArray?, timestampMs: Long): SensorSample? {
        if (bytes == null || bytes.size < PACKET_SIZE_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val deviceMillis = buf.int.toLong() and 0xFFFFFFFFL
        val ir = buf.int
        val red = buf.int
        val accX = buf.short / 1000f
        val accY = buf.short / 1000f
        val accZ = buf.short / 1000f
        return SensorSample(
            timestampMs = timestampMs,
            ir = ir,
            red = red,
            accX = accX,
            accY = accY,
            accZ = accZ,
            sourceTimeSec = deviceMillis / 1000.0
        )
    }
}
