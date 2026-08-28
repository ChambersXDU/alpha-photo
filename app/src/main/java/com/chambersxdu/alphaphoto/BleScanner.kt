package com.chambersxdu.alphaphoto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

data class BleObservation(
    val name: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerData: Map<Int, String>,
    val rawAdvertisement: String,
)

class BleScanner(context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var scanCallback: ScanCallback? = null
    private val loggedAddresses = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    fun start(
        onObservation: (BleObservation) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        check(scanCallback == null)

        val adapter = bluetoothManager.adapter
        if (!adapter.isEnabled) {
            onError("Bluetooth is off.")
            return false
        }

        val scanner = adapter.bluetoothLeScanner
        loggedAddresses.clear()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val observation = result.toObservation()

                if (loggedAddresses.add(observation.address)) {
                    Log.i(
                        TAG,
                        "BLE name=${observation.name} address=${observation.address} " +
                            "rssi=${observation.rssi} services=${observation.serviceUuids} " +
                            "manufacturer=${observation.manufacturerData} " +
                            "raw=${observation.rawAdvertisement}",
                    )
                }

                onObservation(observation)
            }

            override fun onScanFailed(errorCode: Int) {
                scanCallback = null
                onError("BLE scan failed with error code $errorCode.")
            }
        }

        scanCallback = callback
        scanner.startScan(
            null,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            callback,
        )
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val callback = scanCallback ?: return
        bluetoothManager.adapter.bluetoothLeScanner.stopScan(callback)
        scanCallback = null
    }

    private fun ScanResult.toObservation(): BleObservation {
        val record = scanRecord
        val manufacturerData = buildMap {
            val data = record?.manufacturerSpecificData
            if (data != null) {
                for (index in 0 until data.size()) {
                    put(data.keyAt(index), data.valueAt(index).toHex())
                }
            }
        }

        return BleObservation(
            name = record?.deviceName,
            address = device.address,
            rssi = rssi,
            serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() },
            manufacturerData = manufacturerData,
            rawAdvertisement = record?.bytes?.toHex().orEmpty(),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte) }

    private companion object {
        const val TAG = "AlphaPhoto"
    }
}
