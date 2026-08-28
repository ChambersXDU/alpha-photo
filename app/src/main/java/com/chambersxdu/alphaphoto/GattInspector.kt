package com.chambersxdu.alphaphoto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.net.MacAddress
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

class GattInspector(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private val pendingReads = ArrayDeque<UUID>()
    private var readStatus: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun bondState(address: MacAddress): Int =
        bluetoothManager.adapter
            .getRemoteDevice(address.toByteArray())
            .bondState

    @SuppressLint("MissingPermission")
    fun createBond(
        address: MacAddress,
        onStatus: (String) -> Unit,
    ) {
        val device = bluetoothManager.adapter.getRemoteDevice(address.toByteArray())
        Log.i(TAG, "Bluetooth bond requested address=$address currentState=${device.bondState}")

        val started = device.createBond()
        Log.i(TAG, "Bluetooth createBond requested=$started")
        postStatus(onStatus, "Bluetooth bond requested. Watch the system pairing UI.")
    }

    @SuppressLint("MissingPermission")
    fun connect(
        address: MacAddress,
        onStatus: (String) -> Unit,
        onReady: () -> Unit,
    ) {
        check(gatt == null)

        val device = bluetoothManager.adapter.getRemoteDevice(address.toByteArray())
        Log.i(
            TAG,
            "GATT connect requested address=$address bondState=${device.bondState}",
        )

        gatt = device.connectGatt(
            appContext,
            false,
            callback(onStatus, onReady),
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    @SuppressLint("MissingPermission")
    fun readCameraWifiInfo(onStatus: (String) -> Unit) {
        check(pendingReads.isEmpty())

        val currentGatt = checkNotNull(gatt)
        val service = checkNotNull(currentGatt.getService(CAMERA_CONTROL_SERVICE))

        for (uuid in WIFI_INFO_CHARACTERISTICS) {
            checkNotNull(service.getCharacteristic(uuid))
            pendingReads.addLast(uuid)
        }

        readStatus = onStatus
        postStatus(onStatus, "Reading Sony Wi-Fi characteristics…")
        readNext(currentGatt)
    }

    @SuppressLint("MissingPermission")
    fun startCameraWifi(onStatus: (String) -> Unit) {
        check(pendingReads.isEmpty())

        val currentGatt = checkNotNull(gatt)
        val service = checkNotNull(currentGatt.getService(CAMERA_CONTROL_SERVICE))
        val characteristic = checkNotNull(service.getCharacteristic(WIFI_START_CHARACTERISTIC))

        val requestStatus = currentGatt.writeCharacteristic(
            characteristic,
            byteArrayOf(0x01),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )

        Log.i(
            TAG,
            "GATT write requested uuid=$WIFI_START_CHARACTERISTIC value=01 " +
                "requestStatus=$requestStatus",
        )

        if (requestStatus == BluetoothStatusCodes.SUCCESS) {
            postStatus(onStatus, "Sony Wi-Fi start command sent.")
        } else {
            postStatus(onStatus, "Sony Wi-Fi start request failed. status=$requestStatus")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        pendingReads.clear()
        readStatus = null
        gatt?.close()
        gatt = null
    }

    private fun callback(
        onStatus: (String) -> Unit,
        onReady: () -> Unit,
    ) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            Log.i(TAG, "GATT state status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    postStatus(onStatus, "GATT connected. Discovering services…")
                    val started = gatt.discoverServices()
                    Log.i(TAG, "GATT discoverServices requested=$started")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    postStatus(onStatus, "GATT disconnected. status=$status")
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int,
        ) {
            Log.i(
                TAG,
                "GATT services discovered status=$status count=${gatt.services.size}",
            )

            for (service in gatt.services) {
                Log.i(TAG, "GATT service uuid=${service.uuid}")

                for (characteristic in service.characteristics) {
                    val descriptors = characteristic.descriptors
                        .joinToString { it.uuid.toString() }

                    Log.i(
                        TAG,
                        "GATT characteristic service=${service.uuid} " +
                            "uuid=${characteristic.uuid} " +
                            "properties=0x${characteristic.properties.toString(16)} " +
                            "descriptors=[$descriptors]",
                    )
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val cameraControl = gatt.getService(CAMERA_CONTROL_SERVICE)
                if (cameraControl != null) {
                    Log.i(TAG, "Sony Camera Control service verified")
                    appContext.mainExecutor.execute(onReady)
                }
            }

            postStatus(
                onStatus,
                "GATT dump complete. Services: ${gatt.services.size}",
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            Log.i(
                TAG,
                "GATT read result uuid=${characteristic.uuid} status=$status " +
                    "hex=${value.toHex()} utf8=${value.toString(Charsets.UTF_8)}",
            )

            readNext(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.i(
                TAG,
                "GATT write result uuid=${characteristic.uuid} status=$status",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun readNext(gatt: BluetoothGatt) {
        val nextUuid = pendingReads.pollFirst()
        if (nextUuid == null) {
            val callback = readStatus
            readStatus = null
            if (callback != null) {
                postStatus(callback, "Sony Wi-Fi characteristic read complete.")
            }
            return
        }

        val service = checkNotNull(gatt.getService(CAMERA_CONTROL_SERVICE))
        val characteristic = checkNotNull(service.getCharacteristic(nextUuid))

        Log.i(TAG, "GATT read requested uuid=$nextUuid")

        if (!gatt.readCharacteristic(characteristic)) {
            Log.e(TAG, "GATT read request rejected uuid=$nextUuid")
            readNext(gatt)
        }
    }

    private fun postStatus(
        onStatus: (String) -> Unit,
        message: String,
    ) {
        appContext.mainExecutor.execute {
            onStatus(message)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte) }

    private companion object {
        const val TAG = "AlphaPhoto"

        val CAMERA_CONTROL_SERVICE: UUID =
            UUID.fromString("8000cc00-cc00-ffff-ffff-ffffffffffff")

        val WIFI_INFO_CHARACTERISTICS = listOf(
            UUID.fromString("0000cc06-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000cc07-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000cc0c-0000-1000-8000-00805f9b34fb"),
        )

        val WIFI_START_CHARACTERISTIC: UUID =
            UUID.fromString("0000cc08-0000-1000-8000-00805f9b34fb")
    }
}
