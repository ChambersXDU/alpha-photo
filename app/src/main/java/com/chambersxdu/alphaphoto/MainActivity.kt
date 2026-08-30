package com.chambersxdu.alphaphoto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

private val wirelessPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AlphaPhotoTheme {
                AlphaPhotoApp()
            }
        }
    }
}

@Composable
private fun AlphaPhotoApp() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContext = context.applicationContext
    val associationManager = remember { CameraAssociationManager(appContext) }
    val gattInspector = remember { GattInspector(appContext) }
    val wifiConnectionManager = remember { WifiConnectionManager(appContext) }
    val ptpIpProbe = remember { PtpIpProbe(appContext) }
    val connectionGate = remember { CameraConnectionGate() }

    var association by remember {
        mutableStateOf(associationManager.currentAssociation())
    }
    val thumbnailStore = remember(association?.id) {
        CameraThumbnailStore(
            context = appContext,
            ptpIpProbe = ptpIpProbe,
            associationId = association?.id ?: 0,
        )
    }
    val exportManager = remember { PhotoExportManager(appContext, ptpIpProbe) }
    val snackbarHostState = remember { SnackbarHostState() }
    var cameraPresent by remember { mutableStateOf(false) }
    var ptpReady by remember { mutableStateOf(false) }
    var cameraPhotos by remember {
        mutableStateOf<List<PtpObjectInfo>>(emptyList())
    }
    var connectionState by remember {
        mutableStateOf(
            if (association == null) {
                CameraConnectionState.UNASSOCIATED
            } else {
                CameraConnectionState.OFFLINE
            },
        )
    }
    var status by remember {
        mutableStateOf(
            if (association == null) {
                resources.getString(R.string.status_first_use)
            } else {
                resources.getString(R.string.status_waiting_for_camera)
            },
        )
    }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var exportingHandle by remember { mutableStateOf<Int?>(null) }

    val model = SupportedCameras.fromAssociationName(association?.displayName)
        ?: SupportedCameras.sonyA7C2

    LaunchedEffect(snackbarMessage) {
        val message = snackbarMessage ?: return@LaunchedEffect
        snackbarMessage = null
        snackbarHostState.showSnackbar(message)
    }

    val showError: (String) -> Unit = { message ->
        status = message
        snackbarMessage = message
    }

    val connectionFailed: (String) -> Unit = { message ->
        ptpIpProbe.close()
        gattInspector.close()
        wifiConnectionManager.release()
        connectionGate.failed()
        ptpReady = false
        connectionState = CameraConnectionState.ERROR
        showError(message)
    }
    val connectionLost: (String) -> Unit = {
        connectionFailed(resources.getString(R.string.error_camera_connection_lost))
    }

    val refreshPhotos: () -> Unit = {
        ptpIpProbe.listCameraPhotos(
            onStatus = { message -> status = localizeStatus(resources, message) },
            onSuccess = { photos ->
                cameraPhotos = photos
                status = resources.getString(
                    R.string.status_photos_read,
                    CameraPhotoCatalog.group(photos).size,
                )
            },
            onError = showError,
        )
    }

    val startWirelessConnection: (Boolean) -> Unit = connection@{ openWifiPanel ->
        when (connectionGate.begin()) {
            CameraConnectionGate.BeginResult.ALREADY_CONNECTING -> {
                status = resources.getString(R.string.status_camera_connecting)
                return@connection
            }
            CameraConnectionGate.BeginResult.ALREADY_READY -> {
                connectionState = CameraConnectionState.CONNECTED
                status = resources.getString(R.string.status_camera_connected, model.shortName)
                return@connection
            }
            CameraConnectionGate.BeginResult.STARTED -> Unit
        }

        val currentAssociation = association
        val address = currentAssociation?.deviceMacAddress
        if (currentAssociation == null || address == null) {
            connectionFailed(resources.getString(R.string.error_incomplete_association))
            return@connection
        }
        if (!wifiConnectionManager.isWifiEnabled()) {
            connectionFailed(resources.getString(R.string.error_wifi_disabled))
            if (openWifiPanel) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
            }
            return@connection
        }

        connectionState = CameraConnectionState.CONNECTING
        status = resources.getString(R.string.status_connecting_camera, model.shortName)
        gattInspector.connectAndGetWifiCredentials(
            address = address,
            onStatus = { message -> status = localizeStatus(resources, message) },
            onCredentials = { credentials ->
                wifiConnectionManager.connect(
                    credentials = credentials,
                    onStatus = { message -> status = localizeStatus(resources, message) },
                    onConnected = { cameraNetwork ->
                        ptpIpProbe.initialize(
                            cameraNetwork = cameraNetwork,
                            onStatus = { message -> status = localizeStatus(resources, message) },
                            onSuccess = {
                                connectionGate.ready()
                                ptpReady = true
                                connectionState = CameraConnectionState.CONNECTED
                                status = resources.getString(
                                    R.string.status_camera_connected_reading,
                                    model.shortName,
                                )
                                thumbnailStore.clear()
                                cameraPhotos = emptyList()
                                refreshPhotos()
                            },
                            onPhotoAdded = { photo ->
                                cameraPhotos = CameraPhotoCatalog.merge(
                                    current = cameraPhotos,
                                    additions = listOf(photo),
                                )
                                status = resources.getString(
                                    R.string.status_new_photo_ready,
                                    photo.filename,
                                )
                            },
                            onDisconnected = connectionLost,
                            onError = connectionFailed,
                        )
                    },
                    onDisconnected = connectionLost,
                    onError = connectionFailed,
                )
            },
            onError = connectionFailed,
        )
    }

    val associationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            status = resources.getString(R.string.status_association_cancelled)
        }
    }

    val associateCamera: () -> Unit = {
        associationManager.associate(
            model = model,
            onPending = { intentSender ->
                associationLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build(),
                )
            },
            onCreated = { info ->
                association = info
                connectionState = CameraConnectionState.OFFLINE
                status = resources.getString(R.string.status_camera_associated)
            },
            onFailure = { message ->
                showError(resources.getString(R.string.error_association_failed, message))
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (wirelessPermissionsGranted(context)) {
            startWirelessConnection(true)
        } else {
            showError(resources.getString(R.string.error_nearby_devices_permission))
        }
    }

    DisposableEffect(association?.id) {
        val currentAssociation = association
        if (currentAssociation == null) {
            onDispose { }
        } else {
            val subscription = CameraPresenceRegistry.listen(
                currentAssociation.id,
            ) { present ->
                appContext.mainExecutor.execute {
                    cameraPresent = present
                    if (
                        !ptpReady &&
                        connectionState != CameraConnectionState.CONNECTING &&
                        connectionState != CameraConnectionState.ERROR
                    ) {
                        connectionState = if (present) {
                            CameraConnectionState.AVAILABLE
                        } else {
                            CameraConnectionState.OFFLINE
                        }
                        status = if (present) {
                            resources.getString(R.string.status_camera_found, model.shortName)
                        } else {
                            resources.getString(R.string.status_waiting_for_camera)
                        }
                    }
                }
            }
            onDispose(subscription::close)
        }
    }

    LaunchedEffect(association?.id) {
        val currentAssociation = association ?: return@LaunchedEffect
        try {
            associationManager.observePresence(currentAssociation)
        } catch (error: Throwable) {
            showError(
                resources.getString(
                    R.string.error_presence_observation_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    LaunchedEffect(cameraPresent, association?.id) {
        if (
            cameraPresent &&
            association != null &&
            !ptpReady &&
            connectionState != CameraConnectionState.ERROR &&
            wirelessPermissionsGranted(context) &&
            wifiConnectionManager.isWifiEnabled()
        ) {
            startWirelessConnection(false)
        }
    }

    DisposableEffect(thumbnailStore) {
        onDispose(thumbnailStore::close)
    }

    DisposableEffect(gattInspector, wifiConnectionManager, ptpIpProbe) {
        onDispose {
            ptpIpProbe.close()
            gattInspector.close()
            wifiConnectionManager.release()
        }
    }

    AlphaPhotoScreen(
        model = model,
        connectionState = connectionState,
        status = status,
        photos = cameraPhotos,
        thumbnailStore = thumbnailStore,
        snackbarHostState = snackbarHostState,
        exportingHandle = exportingHandle,
        onConnect = {
            if (association == null) {
                associateCamera()
            } else if (wirelessPermissionsGranted(context)) {
                startWirelessConnection(true)
            } else {
                permissionLauncher.launch(wirelessPermissions)
            }
        },
        onRefreshPhotos = refreshPhotos,
        onExport = { photo ->
            exportingHandle = photo.handle
            status = resources.getString(R.string.status_exporting, photo.filename)
            exportManager.export(
                photo = photo,
                onSuccess = {
                    exportingHandle = null
                    status = resources.getString(R.string.status_exported, photo.filename)
                    snackbarMessage =
                        resources.getString(R.string.export_saved_path, photo.filename)
                },
                onError = { message ->
                    exportingHandle = null
                    showError(message)
                },
            )
        },
    )
}

private fun wirelessPermissionsGranted(context: android.content.Context): Boolean =
    wirelessPermissions.all { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

private fun localizeStatus(resources: Resources, message: String): String = when {
    message.startsWith("Connecting to camera over Bluetooth") ->
        resources.getString(R.string.status_connecting_over_bluetooth)
    message.startsWith("Discovering camera Bluetooth services") ->
        resources.getString(R.string.status_checking_camera_services)
    message.startsWith("Starting camera Wi-Fi") ->
        resources.getString(R.string.status_starting_camera_wifi)
    message.startsWith("Waiting for camera Wi-Fi") ->
        resources.getString(R.string.status_waiting_for_camera_wifi)
    message.startsWith("Camera Wi-Fi credentials received") ->
        resources.getString(R.string.status_camera_credentials_received)
    message.startsWith("Joining camera Wi-Fi") ->
        resources.getString(R.string.status_joining_camera_wifi)
    message.startsWith("Camera Wi-Fi connected") ->
        resources.getString(R.string.status_camera_wifi_connected)
    message.startsWith("Waiting for Sony PTP/IP") ->
        resources.getString(R.string.status_establishing_photo_channel)
    message.startsWith("Sony transfer session ready") ->
        resources.getString(R.string.status_photo_channel_ready)
    message.startsWith("Reading camera storage") ->
        resources.getString(R.string.status_reading_camera_storage)
    message.startsWith("Reading metadata") ->
        resources.getString(R.string.status_organizing_camera_photos)
    else -> message
}
