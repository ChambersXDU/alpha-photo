package com.chambersxdu.alphaphoto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.net.MacAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

data class CameraWifiCredentials(
    val ssid: String?,
    val password: String,
    val bssid: String?,
)

class GattInspector(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var bootstrapState = BootstrapState.IDLE
    private var onStatus: ((String) -> Unit)? = null
    private var onCredentials: ((CameraWifiCredentials) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private var ssid: String? = null
    private var password = ""

    private val wifiLaunchTimeout = Runnable {
        if (bootstrapState == BootstrapState.WAITING_FOR_WIFI) {
            fail("Camera Wi-Fi launch timed out.")
        }
    }

    @SuppressLint("MissingPermission")
    fun bondState(address: MacAddress): Int =
        bluetoothManager.adapter
            .getRemoteDevice(address.toByteArray())
            .bondState

    @SuppressLint("MissingPermission")
    fun connectAndGetWifiCredentials(
        address: MacAddress,
        onStatus: (String) -> Unit,
        onCredentials: (CameraWifiCredentials) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (gatt != null || bootstrapState != BootstrapState.IDLE) {
            onError("Camera Bluetooth connection is already in progress.")
            return
        }

        this.onStatus = onStatus
        this.onCredentials = onCredentials
        this.onError = onError

        try {
            val device =
                bluetoothManager.adapter.getRemoteDevice(address.toByteArray())
            Log.i(
                TAG,
                "GATT connect requested address=$address bondState=${device.bondState}",
            )
            postStatus("Connecting to camera over Bluetooth…")

            gatt = device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
        } catch (error: Throwable) {
            fail(
                "Bluetooth connection could not start: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        handler.removeCallbacks(wifiLaunchTimeout)
        gatt?.close()
        gatt = null
        bootstrapState = BootstrapState.IDLE
        onStatus = null
        onCredentials = null
        onError = null
        ssid = null
        password = ""
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            Log.i(TAG, "GATT state status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (
                        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        fail("Bluetooth permission was revoked.")
                        return
                    }

                    postStatus("Bluetooth connected. Discovering Sony services…")
                    val started = gatt.discoverServices()
                    Log.i(TAG, "GATT discoverServices requested=$started")
                    if (!started) {
                        fail("Bluetooth service discovery request was rejected.")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (bootstrapState == BootstrapState.COMPLETE) {
                        Log.i(
                            TAG,
                            "Bluetooth disconnected after Wi-Fi handoff. status=$status",
                        )
                    } else {
                        fail("Bluetooth disconnected. status=$status")
                    }
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Bluetooth service discovery failed. status=$status")
                return
            }

            val service = gatt.getService(CAMERA_CONTROL_SERVICE)
            if (service == null) {
                fail("Sony Camera Control Bluetooth service was not found.")
                return
            }

            val requiredCharacteristics = listOf(
                WIFI_STATUS_CHARACTERISTIC,
                WIFI_START_CHARACTERISTIC,
                WIFI_SSID_CHARACTERISTIC,
                WIFI_PASSWORD_CHARACTERISTIC,
                WIFI_BSSID_CHARACTERISTIC,
            )
            val missingCharacteristic =
                requiredCharacteristics.firstOrNull { uuid ->
                    service.getCharacteristic(uuid) == null
                }
            if (missingCharacteristic != null) {
                fail(
                    "Sony Bluetooth characteristic is missing: " +
                        missingCharacteristic,
                )
                return
            }

            Log.i(TAG, "Sony Camera Control service verified")
            subscribeToWifiStatus(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.i(
                TAG,
                "GATT descriptor write uuid=${descriptor.uuid} status=$status",
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Sony Wi-Fi status subscription failed. status=$status")
                return
            }

            if (bootstrapState != BootstrapState.ENABLING_STATUS) {
                Log.w(
                    TAG,
                    "Ignoring stale GATT descriptor callback in state=" +
                        bootstrapState,
                )
                return
            }
            bootstrapState = BootstrapState.READING_STATUS
            readCharacteristic(gatt, WIFI_STATUS_CHARACTERISTIC)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            Log.i(
                TAG,
                "GATT read result uuid=${characteristic.uuid} status=$status hex=${value.toHex()}",
            )

            when (characteristic.uuid) {
                WIFI_STATUS_CHARACTERISTIC -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        logCameraStatus(value)
                        val wifiStatus = SonyWifiProtocol.parseStatus(value)
                        if (wifiStatus != null) {
                            handleWifiStatus(gatt, wifiStatus)
                            return
                        }
                    }
                    startCameraWifi(gatt)
                }

                WIFI_SSID_CHARACTERISTIC -> {
                    ssid = if (status == BluetoothGatt.GATT_SUCCESS) {
                        SonyWifiProtocol.decodeHeaderAscii(value).ifEmpty { null }
                    } else {
                        Log.i(TAG, "Sony Wi-Fi SSID unavailable status=$status")
                        null
                    }

                    if (ssid != null) {
                        Log.i(TAG, "Sony Wi-Fi SSID=$ssid")
                    }

                    bootstrapState = BootstrapState.READING_PASSWORD
                    readCharacteristic(gatt, WIFI_PASSWORD_CHARACTERISTIC)
                }

                WIFI_PASSWORD_CHARACTERISTIC -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Sony Wi-Fi password read failed. status=$status")
                        return
                    }

                    password = SonyWifiProtocol.decodeHeaderAscii(value)
                    if (password.isEmpty()) {
                        fail("Sony Wi-Fi password was empty.")
                        return
                    }

                    Log.i(TAG, "Sony Wi-Fi password received length=${password.length}")
                    bootstrapState = BootstrapState.READING_BSSID
                    readCharacteristic(gatt, WIFI_BSSID_CHARACTERISTIC)
                }

                WIFI_BSSID_CHARACTERISTIC -> {
                    val bssid = if (status == BluetoothGatt.GATT_SUCCESS) {
                        SonyWifiProtocol.decodePlainAscii(value).ifEmpty { null }
                    } else {
                        Log.i(TAG, "Sony Wi-Fi BSSID unavailable status=$status")
                        null
                    }

                    if (ssid == null && bssid == null) {
                        fail("Camera returned neither SSID nor BSSID.")
                        return
                    }

                    completeCredentials(bssid)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != WIFI_STATUS_CHARACTERISTIC) {
                return
            }

            Log.i(
                TAG,
                "GATT notification uuid=${characteristic.uuid} hex=${value.toHex()}",
            )

            logCameraStatus(value)
            val wifiStatus = SonyWifiProtocol.parseStatus(value) ?: return
            handleWifiStatus(gatt, wifiStatus)
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

            if (characteristic.uuid == WIFI_START_CHARACTERISTIC) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Sony Wi-Fi start command failed. status=$status")
                    return
                }

                handler.postDelayed(wifiLaunchTimeout, WIFI_LAUNCH_TIMEOUT_MS)
                postStatus("Camera Wi-Fi is starting…")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToWifiStatus(gatt: BluetoothGatt) {
        val characteristic =
            sonyCharacteristic(gatt, WIFI_STATUS_CHARACTERISTIC) ?: return
        val descriptor =
            characteristic.getDescriptor(CLIENT_CONFIGURATION_DESCRIPTOR)
        if (descriptor == null) {
            fail("Sony Wi-Fi status notification descriptor was not found.")
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail("Sony Wi-Fi status notification registration failed.")
            return
        }

        bootstrapState = BootstrapState.ENABLING_STATUS
        val requestStatus = gatt.writeDescriptor(
            descriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        )
        Log.i(TAG, "GATT enable CC09 notifications requestStatus=$requestStatus")
        if (requestStatus != BluetoothStatusCodes.SUCCESS) {
            fail("Sony Wi-Fi status descriptor write was rejected. status=$requestStatus")
            return
        }

        postStatus("Listening for camera Wi-Fi status…")
    }

    @SuppressLint("MissingPermission")
    private fun startCameraWifi(gatt: BluetoothGatt) {
        if (
            bootstrapState == BootstrapState.WAITING_FOR_WIFI ||
            bootstrapState == BootstrapState.READING_SSID ||
            bootstrapState == BootstrapState.READING_PASSWORD ||
            bootstrapState == BootstrapState.READING_BSSID
        ) {
            return
        }

        val characteristic =
            sonyCharacteristic(gatt, WIFI_START_CHARACTERISTIC) ?: return

        bootstrapState = BootstrapState.WAITING_FOR_WIFI
        val requestStatus = gatt.writeCharacteristic(
            characteristic,
            byteArrayOf(0x01),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )

        Log.i(
            TAG,
            "GATT write requested uuid=$WIFI_START_CHARACTERISTIC value=01 requestStatus=$requestStatus",
        )
        if (requestStatus != BluetoothStatusCodes.SUCCESS) {
            fail("Sony Wi-Fi start request was rejected. status=$requestStatus")
        }
    }

    private fun logCameraStatus(value: ByteArray) {
        val imageTransferState =
            SonyWifiProtocol.parseImageTransferState(value) ?: return
        Log.i(TAG, "Sony image transfer state=$imageTransferState")
    }

    private fun handleWifiStatus(
        gatt: BluetoothGatt,
        status: SonyWifiStatus,
    ) {
        Log.i(TAG, "Sony Wi-Fi state=${status.state} error=${status.error}")

        if (status.state == WIFI_STATE_LAUNCHED) {
            handler.removeCallbacks(wifiLaunchTimeout)
            if (
                bootstrapState != BootstrapState.READING_SSID &&
                bootstrapState != BootstrapState.READING_PASSWORD &&
                bootstrapState != BootstrapState.READING_BSSID &&
                bootstrapState != BootstrapState.COMPLETE
            ) {
                bootstrapState = BootstrapState.READING_SSID
                postStatus("Camera Wi-Fi is ready. Reading credentials…")
                readCharacteristic(gatt, WIFI_SSID_CHARACTERISTIC)
            }
            return
        }

        if (bootstrapState == BootstrapState.READING_STATUS) {
            startCameraWifi(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(
        gatt: BluetoothGatt,
        uuid: UUID,
    ) {
        val characteristic = sonyCharacteristic(gatt, uuid) ?: return

        Log.i(TAG, "GATT read requested uuid=$uuid")
        if (!gatt.readCharacteristic(characteristic)) {
            fail("Sony GATT read request was rejected. uuid=$uuid")
        }
    }

    private fun completeCredentials(bssid: String?) {
        bootstrapState = BootstrapState.COMPLETE
        Log.i(TAG, "Sony Wi-Fi credentials ready ssid=$ssid bssid=$bssid")
        postStatus("Camera Wi-Fi credentials received.")

        val callback = onCredentials
        if (callback == null) {
            Log.w(TAG, "Ignoring late Sony Wi-Fi credentials callback.")
            return
        }

        appContext.mainExecutor.execute {
            callback(
                CameraWifiCredentials(
                    ssid = ssid,
                    password = password,
                    bssid = bssid,
                ),
            )
        }
    }

    private fun sonyCharacteristic(
        gatt: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? {
        val service = gatt.getService(CAMERA_CONTROL_SERVICE)
        if (service == null) {
            fail("Sony Camera Control Bluetooth service is unavailable.")
            return null
        }

        val characteristic = service.getCharacteristic(uuid)
        if (characteristic == null) {
            fail("Sony Bluetooth characteristic is unavailable: $uuid")
            return null
        }

        return characteristic
    }

    @SuppressLint("MissingPermission")
    private fun fail(message: String) {
        handler.removeCallbacks(wifiLaunchTimeout)
        Log.e(TAG, message)

        val callback = onError
        val activeGatt = gatt

        gatt = null
        bootstrapState = BootstrapState.IDLE
        onStatus = null
        onCredentials = null
        onError = null
        ssid = null
        password = ""

        activeGatt?.close()

        if (callback != null) {
            appContext.mainExecutor.execute {
                callback(message)
            }
        }
    }

    private fun postStatus(message: String) {
        val callback = onStatus ?: return
        appContext.mainExecutor.execute {
            callback(message)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte) }

    private enum class BootstrapState {
        IDLE,
        ENABLING_STATUS,
        READING_STATUS,
        WAITING_FOR_WIFI,
        READING_SSID,
        READING_PASSWORD,
        READING_BSSID,
        COMPLETE,
    }

    private companion object {
        const val TAG = "AlphaPhoto"
        const val WIFI_STATE_LAUNCHED = 2
        const val WIFI_LAUNCH_TIMEOUT_MS = 30_000L

        val CAMERA_CONTROL_SERVICE: UUID =
            UUID.fromString("8000cc00-cc00-ffff-ffff-ffffffffffff")

        val WIFI_SSID_CHARACTERISTIC: UUID =
            UUID.fromString("0000cc06-0000-1000-8000-00805f9b34fb")

        val WIFI_PASSWORD_CHARACTERISTIC: UUID =
            UUID.fromString("0000cc07-0000-1000-8000-00805f9b34fb")

        val WIFI_START_CHARACTERISTIC: UUID =
            UUID.fromString("0000cc08-0000-1000-8000-00805f9b34fb")

        val WIFI_STATUS_CHARACTERISTIC: UUID =
            UUID.fromString("0000cc09-0000-1000-8000-00805f9b34fb")

        val WIFI_BSSID_CHARACTERISTIC: UUID =
            UUID.fromString("0000cc0c-0000-1000-8000-00805f9b34fb")

        val CLIENT_CONFIGURATION_DESCRIPTOR: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
