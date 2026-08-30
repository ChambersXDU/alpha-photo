package com.chambersxdu.alphaphoto

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import java.util.regex.Pattern

internal class CameraAssociationManager(context: Context) {
    private val appContext = context.applicationContext
    private val deviceManager = appContext.getSystemService(CompanionDeviceManager::class.java)

    fun currentAssociation(): AssociationInfo? =
        deviceManager.myAssociations.firstOrNull {
            SupportedCameras.fromAssociationName(it.displayName) != null
        }

    fun observePresence(association: AssociationInfo) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(association.id)
            .build()
        deviceManager.startObservingDevicePresence(request)
    }

    fun associate(
        model: CameraModel,
        onPending: (IntentSender) -> Unit,
        onCreated: (AssociationInfo) -> Unit,
        onFailure: (CharSequence?) -> Unit,
    ) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(
                Pattern.compile("^${Pattern.quote(model.associationName)}$"),
            )
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        deviceManager.associate(
            request,
            appContext.mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    onPending(intentSender)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    onCreated(associationInfo)
                }

                override fun onFailure(errorMessage: CharSequence?) {
                    onFailure(errorMessage)
                }
            },
        )
    }

}
