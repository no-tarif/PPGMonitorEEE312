package com.eee312.ppgmonitor.sensor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.eee312.ppgmonitor.model.SensorSample
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/**
 * Wired-USB client for the ESP32 sensor rig. The phone acts as USB host,
 * the ESP32 as a USB-serial device (either through a bridge chip like the
 * CP2102/CH340, or native USB-CDC on ESP32-S2/S3/C3 boards) - see
 * USB_SERIAL_CONTRACT.md (project root) for the line format this parses
 * against, share that with whoever owns the ESP32 firmware.
 *
 * Uses usb-serial-for-android's generic UsbSerialProber rather than
 * hardcoding a specific chip driver, so this works regardless of which
 * ESP32 variant/board the hardware side ends up using.
 *
 * STATUS: structurally complete (find device -> request permission ->
 * open -> read -> parse) but UNTESTED against real firmware - only
 * parseLine() and PACKET_SIZE-equivalent assumptions below need to change
 * if the real firmware's line format differs; the USB plumbing above it
 * is generic serial-port code and does not depend on the payload format.
 *
 * The caller (MainActivity) does NOT need to request any Android runtime
 * permission before calling start() - USB device access uses its own
 * UsbManager-mediated permission dialog, requested here per-device the
 * first time it's opened.
 */
class UsbSerialSensorSource(
    private val context: Context
) : SensorSource {

    // Nominal rate of PpgStream.ino (HARDWARE_SETUP_GUIDE.md). The app
    // measures the real rate from arrival times / the frame's seq field
    // and rebuilds the DSP if the rig runs off-nominal (MainActivity).
    override val sampleRateHz: Double = HardwareProfile.NOMINAL_RATE_HZ

    companion object {
        private const val TAG = "UsbSerialSensorSource"
        private const val ACTION_USB_PERMISSION = "com.eee312.ppgmonitor.USB_PERMISSION"

        // 115200 8N1 is the ESP32 Arduino core's default Serial.begin() rate
        // and what most example sketches use - flag to the firmware owner
        // if this needs to be configurable/different.
        private const val BAUD_RATE = 115200

        // Guards against unbounded memory growth if the firmware never sends
        // a newline (e.g. binary garbage on connect) - discard and resync.
        private const val MAX_LINE_BUFFER_CHARS = 1024
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var pendingDriver: UsbSerialDriver? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var onSampleCb: ((SensorSample) -> Unit)? = null
    private var onStatusCb: ((String) -> Unit)? = null
    private var permissionReceiverRegistered = false

    // Accumulates raw bytes across read callbacks until complete newline-
    // delimited lines can be split off - serial reads arrive as arbitrary
    // byte chunks, not pre-split into one chunk per line.
    private val lineBuffer = StringBuilder()

    // Arrival time of the first parsed line (elapsedRealtimeNanos); every
    // sample's sourceTimeSec is measured from it.
    private var firstArrivalNs = -1L

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            unregisterPermissionReceiver()

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val driver = pendingDriver
            if (granted && driver != null) {
                openDevice(driver)
            } else {
                onStatusCb?.invoke("USB permission denied - cannot use ESP32 (USB) source")
            }
        }
    }

    override fun start(onSample: (SensorSample) -> Unit, onStatus: (String) -> Unit) {
        onSampleCb = onSample
        onStatusCb = onStatus
        lineBuffer.setLength(0)
        firstArrivalNs = -1L

        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            onStatus("No USB serial device found - plug in the ESP32 over USB-C")
            return
        }
        pendingDriver = driver

        if (usbManager.hasPermission(driver.device)) {
            openDevice(driver)
        } else {
            onStatus("Requesting USB permission...")
            requestPermission(driver.device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        permissionReceiverRegistered = true

        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(driver: UsbSerialDriver) {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            onStatusCb?.invoke("Failed to open USB connection to ${driver.device.deviceName}")
            return
        }

        val p = driver.ports.firstOrNull()
        if (p == null) {
            onStatusCb?.invoke("USB serial device has no ports")
            return
        }

        try {
            p.open(connection)
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: IOException) {
            onStatusCb?.invoke("Failed to open USB serial port: ${e.message}")
            return
        }
        port = p

        val manager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                handleBytes(data)
            }

            override fun onRunError(e: Exception) {
                // The IoManager's read thread exits right after this callback
                // (typically because the device was unplugged) - just report
                // it and drop our port reference, don't call stop() from here.
                Log.w(TAG, "USB serial run error (device likely unplugged)", e)
                onStatusCb?.invoke("ESP32 (USB) disconnected")
                port = null
            }
        })
        ioManager = manager
        manager.start()
        onStatusCb?.invoke("Connected, reading over USB serial at $BAUD_RATE baud...")
    }

    private fun handleBytes(data: ByteArray) {
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        lineBuffer.append(String(data, Charsets.US_ASCII))

        while (true) {
            val newlineIdx = lineBuffer.indexOf("\n")
            if (newlineIdx < 0) break
            val line = lineBuffer.substring(0, newlineIdx).trim()
            lineBuffer.delete(0, newlineIdx + 1)
            if (line.isNotEmpty()) {
                parseLine(line, arrivalNs)?.let { onSampleCb?.invoke(it) }
            }
        }

        if (lineBuffer.length > MAX_LINE_BUFFER_CHARS) {
            Log.w(TAG, "Line buffer exceeded $MAX_LINE_BUFFER_CHARS chars without a newline - discarding and resyncing")
            lineBuffer.setLength(0)
        }
    }

    /** One line -> sample, via [SerialLineParser] (5, 6 or 7 fields, see
     * USB_SERIAL_CONTRACT.md). The firmware's own '#' messages (e.g.
     * "# INIT FAILED: MPU6050 did not answer") are shown as the status. */
    private fun parseLine(line: String, arrivalNs: Long): SensorSample? {
        if (firstArrivalNs < 0) firstArrivalNs = arrivalNs
        val arrivalSec = (arrivalNs - firstArrivalNs) / 1e9
        return when (val r = SerialLineParser.parse(line, arrivalNs / 1_000_000L, arrivalSec)) {
            is SerialLineParser.Result.Sample -> r.sample
            is SerialLineParser.Result.Comment -> {
                Log.i(TAG, "Firmware: ${r.text}")
                onStatusCb?.invoke("ESP32: ${r.text.removePrefix("#").trim()}")
                null
            }
            is SerialLineParser.Result.Malformed -> {
                Log.w(TAG, "Skipping malformed line (${r.reason}): $line")
                null
            }
        }
    }

    private fun unregisterPermissionReceiver() {
        if (!permissionReceiverRegistered) return
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered - nothing to do
        }
        permissionReceiverRegistered = false
    }

    override fun stop() {
        unregisterPermissionReceiver()
        ioManager?.stop()
        ioManager = null
        try {
            port?.close()
        } catch (e: IOException) {
            // already disconnected - nothing to do
        }
        port = null
        pendingDriver = null
    }
}
