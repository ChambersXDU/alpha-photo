package com.chambersxdu.alphaphoto

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPresenceRegistryTest {
    @Test
    fun listenerReceivesCurrentStateAndStopsAfterClosing() {
        val associationId = 99_001
        val observed = mutableListOf<Boolean>()
        val subscription = CameraPresenceRegistry.listen(associationId) { present ->
            observed += present
        }

        CameraPresenceRegistry.update(associationId, true)
        CameraPresenceRegistry.update(associationId, false)
        subscription.close()
        CameraPresenceRegistry.update(associationId, true)

        assertEquals(listOf(false, true, false), observed)

        CameraPresenceRegistry.update(associationId, false)
    }

    @Test
    fun newListenerReceivesAlreadyPresentState() {
        val associationId = 99_002
        CameraPresenceRegistry.update(associationId, true)
        val observed = mutableListOf<Boolean>()

        CameraPresenceRegistry.listen(associationId) { present ->
            observed += present
        }.close()

        assertEquals(listOf(true), observed)

        CameraPresenceRegistry.update(associationId, false)
    }
}
