package com.chambersxdu.alphaphoto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

class GattInspector(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun connect(
        address: String,
        onStatus: (String) -> Unit,
    ) {
        check(gatt == null)

        val device = bluetoothManager.adapter.getRemoteDevice(address)
        Log.i(TAG, "GATT connect requested address=$address")

        gatt = device.connectGatt(
            appContext,
            false,
            callback(onStatus),
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    @SuppressLint("MissingPermission")
    fun close() {
        gatt?.close()
        gatt = null
    }

    private fun callback(onStatus: (String) -> Unit) =
        object : BluetoothGattCallback() {
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

                postStatus(
                    onStatus,
                    "GATT dump complete. Services: ${gatt.services.size}",
                )
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

    private companion object {
        const val TAG = "AlphaPhoto"
    }
}
