package com.chambersxdu.alphaphoto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
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

private val wirelessPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES,
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

    val associationManager = remember {
        CameraAssociationManager(context.applicationContext)
    }
    val gattInspector = remember {
        GattInspector(context.applicationContext)
    }
    val wifiConnectionManager = remember {
        WifiConnectionManager(context.applicationContext)
    }
    val ptpIpProbe = remember {
        PtpIpProbe(context.applicationContext)
    }

    var association by remember {
        mutableStateOf(associationManager.currentAssociation())
    }
    var ptpReady by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (association == null) {
                "Associate the a7C II once to start."
            } else {
                "Ready to connect wirelessly."
            },
        )
    }

    val associationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        status = if (result.resultCode == Activity.RESULT_OK) {
            "Association confirmed."
        } else {
            "Association dialog closed."
        }
    }

    val startWirelessConnection = {
        val currentAssociation = checkNotNull(association)
        val address = checkNotNull(currentAssociation.deviceMacAddress)

        if (!wifiConnectionManager.isWifiEnabled()) {
            status =
                "Turn on phone Wi-Fi, then tap Connect wirelessly again."
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } else {
            gattInspector.connectAndGetWifiCredentials(
                address = address,
                onStatus = { message ->
                    status = message
                },
                onCredentials = { credentials ->
                    wifiConnectionManager.connect(
                        credentials = credentials,
                        onStatus = { message ->
                            status = message
                        },
                        onConnected = { cameraNetwork ->
                            ptpIpProbe.initialize(
                                cameraNetwork = cameraNetwork,
                                onStatus = { message ->
                                    status = message
                                },
                                onSuccess = {
                                    ptpReady = true
                                    status =
                                        "Wireless path ready: BLE → Wi-Fi → Sony PTP."
                                },
                                onError = { message ->
                                    status = message
                                },
                            )
                        },
                        onError = { message ->
                            status = message
                        },
                    )
                },
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val granted = wirelessPermissions.all { permission ->
            context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
        }

        if (granted) {
            startWirelessConnection()
        } else {
            status =
                "Bluetooth and Nearby Wi-Fi permissions are required."
        }
    }

    DisposableEffect(gattInspector, wifiConnectionManager, ptpIpProbe) {
        onDispose {
            ptpIpProbe.close()
            gattInspector.close()
            wifiConnectionManager.release()
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
                    text = "Sony wireless connection spike",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (association != null) {
                    Text(
                        text =
                            "${association?.displayName} · ${association?.deviceMacAddress}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (association == null) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            associationManager.associate(
                                onPending = { intentSender ->
                                    associationLauncher.launch(
                                        IntentSenderRequest
                                            .Builder(intentSender)
                                            .build(),
                                    )
                                },
                                onCreated = { info ->
                                    association = info
                                    status =
                                        "Associated ${info.displayName}."
                                },
                                onFailure = { message ->
                                    status =
                                        "Association failed: $message"
                                },
                            )
                        },
                    ) {
                        Text("Associate a7C II")
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = association != null,
                    onClick = {
                        val permissionsGranted =
                            wirelessPermissions.all { permission ->
                                context.checkSelfPermission(permission) ==
                                    PackageManager.PERMISSION_GRANTED
                            }

                        if (permissionsGranted) {
                            startWirelessConnection()
                        } else {
                            permissionLauncher.launch(wirelessPermissions)
                        }
                    },
                ) {
                    Text("Connect wirelessly")
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ptpReady,
                    onClick = {
                        ptpIpProbe.listCameraPhotos(
                            onStatus = { message ->
                                status = message
                            },
                            onSuccess = { photos ->
                                status =
                                    "Camera: ${photos.size} JPEG/RAW/HEIF photos."
                            },
                            onError = { message ->
                                status = message
                            },
                        )
                    },
                ) {
                    Text("List camera photos")
                }

                Text(
                    text =
                        "Connect runs BLE → camera Wi-Fi → full Sony PTP transfer-session setup. " +
                            "Photo listing uses standard PTP GetObjectHandles/GetObjectInfo in Sony content-transfer mode.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
