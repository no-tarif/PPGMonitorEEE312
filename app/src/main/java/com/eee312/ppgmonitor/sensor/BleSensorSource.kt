package com.eee312.ppgmonitor.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.SystemClock
import com.eee312.ppgmonitor.model.SensorSample
import java.util.UUID

/**
 * BLE client for the ESP32 sensor rig. The packet contract is in
 * docs/ESP32_LINK_GUIDE.md (Q3) and [BlePacketParser]. NOTE: the rig in
 * HARDWARE_SETUP_GUIDE.md has NO BLE firmware (PpgStream.ino is USB-only),
 * so nothing currently sends these packets.
 *
 * STATUS: structurally complete (scan -> connect -> discover -> enable
 * notifications -> parse) but UNTESTED against real firmware - at the
 * time this was written it wasn't yet known whether the ESP32 already
 * streams over BLE or only logs to CSV for later analysis (like
 * walk_clean.csv). If the real firmware uses a different packet layout,
 * only PACKET_SIZE_BYTES and parsePacket() below need to change -
 * everything else (scan/connect/notify plumbing) is generic BLE central
 * code and does not depend on the payload format.
 *
 * Uses Nordic UART Service (NUS) UUIDs deliberately - see
 * docs/ESP32_LINK_GUIDE.md for why.
 *
 * The caller (MainActivity) is responsible for requesting the BLE
 * runtime permissions (BLUETOOTH_SCAN/BLUETOOTH_CONNECT on API 31+,
 * or BLUETOOTH/BLUETOOTH_ADMIN/ACCESS_FINE_LOCATION below that) BEFORE
 * calling start() - hence the suppression below rather than scattering
 * permission checks through this class.
 */
@SuppressLint("MissingPermission")
class BleSensorSource(
    private val context: Context,
    /** Substring to match against the advertised device name - change
     * to whatever the ESP32 firmware actually advertises. */
    private val targetDeviceNamePrefix: String = "PPG"
) : SensorSource {

    override val sampleRateHz: Double = HardwareProfile.NOMINAL_RATE_HZ // measured and followed at runtime

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PACKET_SIZE_BYTES = BlePacketParser.PACKET_SIZE_BYTES
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var onSampleCb: ((SensorSample) -> Unit)? = null
    private var onStatusCb: ((String) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.contains(targetDeviceNamePrefix, ignoreCase = true)) return

            onStatusCb?.invoke("Found $name, connecting...")
            scanner?.stopScan(this)
            bluetoothGatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            onStatusCb?.invoke("BLE scan failed, error $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatusCb?.invoke("Connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> onStatusCb?.invoke("Disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusCb?.invoke("Service discovery failed, status $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                onStatusCb?.invoke("Service $SERVICE_UUID not found - check firmware UUIDs against docs/ESP32_LINK_GUIDE.md")
                return
            }
            val txChar = service.getCharacteristic(TX_CHARACTERISTIC_UUID)
            if (txChar == null) {
                onStatusCb?.invoke("TX characteristic not found - check firmware UUIDs against docs/ESP32_LINK_GUIDE.md")
                return
            }

            gatt.setCharacteristicNotification(txChar, true)
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            onStatusCb?.invoke("Subscribed - waiting for samples")
        }

        @Deprecated("Deprecated in API 33; kept so this also runs on older devices")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handlePacket(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handlePacket(value) // API 33+ path
        }
    }

    private fun handlePacket(bytes: ByteArray?) {
        parsePacket(bytes)?.let { onSampleCb?.invoke(it) }
    }

    /** See [BlePacketParser] for the 18-byte layout (and its +/-3.3 g limit). */
    private fun parsePacket(bytes: ByteArray?): SensorSample? =
        BlePacketParser.parse(bytes, SystemClock.elapsedRealtime())

    override fun start(onSample: (SensorSample) -> Unit, onStatus: (String) -> Unit) {
        onSampleCb = onSample
        onStatusCb = onStatus

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off or unavailable")
            return
        }

        scanner = adapter.bluetoothLeScanner
        onStatus("Scanning for a device advertising as \"$targetDeviceNamePrefix*\"...")
        scanner?.startScan(scanCallback)
    }

    override fun stop() {
        scanner?.stopScan(scanCallback)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
