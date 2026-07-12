package com.mughni.sbmsbridge

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * Connects to the BMS (51112BS34001242) the same way nRF Connect does: as a second
 * BluetoothGatt client, WITHOUT sending any login/auth command. This only works if the
 * official JK BMS app has already connected and unlocked the device at least once
 * recently — the device's "unlocked" state appears to be device-wide, not tied to a
 * specific connection, which is exactly why nRF Connect could read real telemetry
 * without ever performing the encrypted handshake itself.
 *
 * Protocol confirmed from real BLE captures (see conversation history):
 *   - Heartbeat:  41 54 0D 0A ("AT\r\n", 4 bytes) — device not yet streaming real data
 *   - Cell frame: 55 AA EB 90 02 [counter] + 12x (uint16 LE, mV) cell voltages (12S LFP)
 *   - Summary frame (no header, starts directly at byte 0):
 *       byte 0-1   total voltage, uint16 LE, x0.001 V
 *       byte 8-9   current, int16 LE, x0.001 A (raw: + while charging; flipped here so
 *                  current>0 = discharge, current<0 = charge, matching the dashboard UI)
 *       byte 12-13 temp sensor 1, int16 LE, x0.1 C
 *       byte 14-15 temp sensor 2, int16 LE, x0.1 C
 *       byte 23    SOC, 1 byte, 0-100
 *       byte 24-27 remaining capacity, uint32 LE, x0.001 Ah
 *       byte 28-31 full/nominal capacity, uint32 LE, x0.001 Ah
 */
class BleConnector(
    private val context: Context,
    private val deviceMac: String,
    private val onData: (String) -> Unit
) {

    companion object {
        private const val TAG = "BleConnector"

        private val SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val HEARTBEAT = byteArrayOf(0x41, 0x54, 0x0D, 0x0A)
        private val CELL_HEADER = byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())
    }

    private var gatt: BluetoothGatt? = null
    @Volatile var isConnected: Boolean = false
        private set

    // last parsed values — used purely to avoid spamming identical pushes
    private var lastVoltage: Double? = null
    private var lastCurrent: Double? = null
    private var lastSoc: Int? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter on this device")
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(deviceMac)) {
            Log.e(TAG, "Invalid MAC address: $deviceMac")
            return
        }
        val device = adapter.getRemoteDevice(deviceMac)
        Log.i(TAG, "Connecting to $deviceMac ...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected, discovering services...")
                isConnected = true
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected (status=$status)")
                isConnected = false
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = g.getService(SERVICE_UUID)
            val char = service?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                Log.e(TAG, "Characteristic ffe1 not found")
                return
            }
            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            // Deliberately NOT sending any activation/login command here — that's the
            // whole point. We rely on the official app having already unlocked the device.
            Log.i(TAG, "Subscribed to ffe1 notifications, waiting for frames...")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleFrame(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value
            if (bytes == null || bytes.isEmpty()) return
            handleFrame(bytes)
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        if (bytes.size == 4 && bytes.contentEquals(HEARTBEAT)) {
            return // device still locked/idle — nothing to parse yet
        }
        if (bytes.size < 30) return

        val isCellFrame = bytes.size >= 4 &&
                bytes[0] == CELL_HEADER[0] && bytes[1] == CELL_HEADER[1] &&
                bytes[2] == CELL_HEADER[2] && bytes[3] == CELL_HEADER[3]

        if (isCellFrame) {
            // cell frame — we don't currently surface individual cell voltages to the
            // dashboard, only the summary frame's aggregate values, so nothing to do here
            return
        }

        // summary frame
        val voltageRaw = u16(bytes, 0) ?: return
        val currentRawSigned = i16(bytes, 8)
        val temp1Raw = i16(bytes, 12)
        val temp2Raw = i16(bytes, 14)
        val socByte = if (bytes.size > 23) bytes[23].toInt() and 0xFF else null
        val remainingAhRaw = u32(bytes, 24)
        val fullCapacityAhRaw = u32(bytes, 28)

        val voltage = voltageRaw * 0.001
        if (voltage < 10 || voltage > 120) return // sanity guard against garbage/unrelated frames

        // NOTE: raw value from the BMS is already positive while charging (confirmed via
        // real capture) and goes negative under discharge/load — so no sign flip needed here.
        // (Earlier we flipped this to match Dashboard.jsx's color convention, but that
        // turned out backwards vs what actually happens on a real load test — fixed now.)
        val current = currentRawSigned?.let { it * 0.001 }
        val power = if (current != null) voltage * current else null
        val soc = socByte?.takeIf { it in 0..100 }
        val remainingAh = remainingAhRaw?.let { it * 0.001 }
        val fullCapacityAh = fullCapacityAhRaw?.let { it * 0.001 }
        val temp1 = temp1Raw?.let { it * 0.1 }
        val temp2 = temp2Raw?.let { it * 0.1 }

        val changed = voltage != lastVoltage || current != lastCurrent || soc != lastSoc
        if (!changed) return
        lastVoltage = voltage; lastCurrent = current; lastSoc = soc

        val obj = JSONObject()
        obj.put("voltage", voltage)
        obj.put("current", current ?: JSONObject.NULL)
        obj.put("power", power ?: JSONObject.NULL)
        obj.put("soc", soc ?: JSONObject.NULL)
        obj.put("remainingAh", remainingAh ?: JSONObject.NULL)
        obj.put("fullCapacityAh", fullCapacityAh ?: JSONObject.NULL)
        obj.put("temp1", temp1 ?: JSONObject.NULL)
        obj.put("temp2", temp2 ?: JSONObject.NULL)
        obj.put("rawText", "BLE summary frame (" + bytes.size + "B): " + bytes.joinToString(" ") { "%02X".format(it) })
        obj.put("timestamp", System.currentTimeMillis())
        onData(obj.toString())
    }

    private fun u16(a: ByteArray, i: Int): Int? {
        if (i + 1 >= a.size) return null
        return (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun i16(a: ByteArray, i: Int): Int? {
        val v = u16(a, i) ?: return null
        return if (v > 32767) v - 65536 else v
    }

    private fun u32(a: ByteArray, i: Int): Long? {
        if (i + 3 >= a.size) return null
        return (a[i].toLong() and 0xFF) or
                ((a[i + 1].toLong() and 0xFF) shl 8) or
                ((a[i + 2].toLong() and 0xFF) shl 16) or
                ((a[i + 3].toLong() and 0xFF) shl 24)
    }
}