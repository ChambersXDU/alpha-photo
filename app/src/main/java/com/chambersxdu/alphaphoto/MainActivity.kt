package com.chambersxdu.alphaphoto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.MacAddress
import android.os.Bundle
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

    var association by remember {
        mutableStateOf(associationManager.currentAssociation())
    }
    var pendingGattAddress by remember { mutableStateOf<MacAddress?>(null) }
    var status by remember {
        mutableStateOf(
            if (association == null) {
                "Put the a7C II in smartphone connection mode, then associate it."
            } else {
                "Camera association found."
            },
        )
    }

    val associationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            status = "Association confirmed. Waiting for Android to finish."
        } else {
            status = "Association dialog closed."
        }
    }

    val connectGatt = { address: MacAddress ->
        gattInspector.connect(address) { message ->
            status = message
        }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            connectGatt(checkNotNull(pendingGattAddress))
            pendingGattAddress = null
        } else {
            status = "Bluetooth permission is required to inspect GATT."
        }
    }

    DisposableEffect(gattInspector) {
        onDispose {
            gattInspector.close()
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
                    text = "Sony companion association spike",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (association != null) {
                    Text(
                        text = "${association?.displayName} · ${association?.deviceMacAddress}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        associationManager.associate(
                            onPending = { intentSender ->
                                associationLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build(),
                                )
                            },
                            onCreated = { info ->
                                association = info
                                status = "Associated ${info.displayName}."
                            },
                            onFailure = { message ->
                                status = "Association failed: $message"
                            },
                        )
                    },
                ) {
                    Text("Associate a7C II")
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = association != null,
                    onClick = {
                        val address = checkNotNull(
                            checkNotNull(association).deviceMacAddress,
                        )

                        if (
                            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            connectGatt(address)
                        } else {
                            pendingGattAddress = address
                            bluetoothPermissionLauncher.launch(
                                Manifest.permission.BLUETOOTH_CONNECT,
                            )
                        }
                    },
                ) {
                    Text("Dump GATT")
                }

                Text(
                    text = "The GATT dump is written to the AlphaPhoto log tag.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
