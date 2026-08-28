package com.chambersxdu.alphaphoto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val blePermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AlphaPhotoApp()
        }
    }
}

@Composable
private fun AlphaPhotoApp() {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context.applicationContext) }

    var devices by remember { mutableStateOf(emptyList<BleObservation>()) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to scan.") }

    val beginScan = {
        devices = emptyList()
        status = "Scanning. Turn the a7C II on now."
        scanning = scanner.start(
            onObservation = { observation ->
                devices = (
                    devices.filterNot { it.address == observation.address } + observation
                    ).sortedByDescending { it.rssi }
            },
            onError = { message ->
                status = message
                scanning = false
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val granted = blePermissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        if (granted) {
            beginScan()
        } else {
            status = "Bluetooth and precise location permissions are required for this discovery test."
        }
    }

    DisposableEffect(scanner) {
        onDispose {
            scanner.stop()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Alpha Photo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "BLE discovery spike",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (scanning) {
                            scanner.stop()
                            scanning = false
                            status = "Scan stopped."
                        } else {
                            val permissionsGranted = blePermissions.all {
                                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                            }

                            if (permissionsGranted) {
                                beginScan()
                            } else {
                                permissionLauncher.launch(blePermissions)
                            }
                        }
                    },
                ) {
                    Text(if (scanning) "Stop scan" else "Start scan")
                }

                Text(
                    text = "Devices: ${devices.size}",
                    style = MaterialTheme.typography.titleMedium,
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = devices,
                        key = { it.address },
                    ) { device ->
                        BleDeviceRow(device)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BleDeviceRow(device: BleObservation) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = device.name ?: "Unnamed device",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "${device.address} · RSSI ${device.rssi}",
            style = MaterialTheme.typography.bodySmall,
        )

        if (device.serviceUuids.isNotEmpty()) {
            Text(
                text = "Services: ${device.serviceUuids.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (device.manufacturerData.isNotEmpty()) {
            Text(
                text = "Manufacturer: " +
                    device.manufacturerData.entries.joinToString { (id, data) -> "$id: $data" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
