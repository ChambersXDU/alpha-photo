package com.chambersxdu.alphaphoto

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log
import java.io.Closeable

internal object CameraPresenceRegistry {
    private val presentAssociations = mutableSetOf<Int>()
    private val listeners = mutableMapOf<Int, MutableSet<(Boolean) -> Unit>>()

    @Synchronized
    fun listen(
        associationId: Int,
        listener: (Boolean) -> Unit,
    ): Closeable {
        listeners.getOrPut(associationId, ::mutableSetOf).add(listener)
        listener(associationId in presentAssociations)

        return Closeable {
            synchronized(this) {
                listeners[associationId]?.remove(listener)
            }
        }
    }

    @Synchronized
    fun update(
        associationId: Int,
        present: Boolean,
    ) {
        if (present) {
            presentAssociations.add(associationId)
        } else {
            presentAssociations.remove(associationId)
        }
        listeners[associationId]?.toList()?.forEach { listener ->
            listener(present)
        }
    }
}

class CameraPresenceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val present = when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED,
            -> true

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED,
            -> false

            else -> return
        }

        Log.i(
            TAG,
            "Companion presence association=${event.associationId} " +
                "event=${event.event} present=$present",
        )
        CameraPresenceRegistry.update(event.associationId, present)
    }

    private companion object {
        const val TAG = "AlphaPhoto"
    }
}
