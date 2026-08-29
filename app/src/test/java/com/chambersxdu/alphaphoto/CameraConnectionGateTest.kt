package com.chambersxdu.alphaphoto

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraConnectionGateTest {
    @Test
    fun duplicateBeginIsRejectedWithoutThrowing() {
        val gate = CameraConnectionGate()

        assertEquals(CameraConnectionGate.BeginResult.STARTED, gate.begin())
        assertEquals(
            CameraConnectionGate.BeginResult.ALREADY_CONNECTING,
            gate.begin(),
        )
    }

    @Test
    fun failureReturnsGateToIdleForRetry() {
        val gate = CameraConnectionGate()

        assertEquals(CameraConnectionGate.BeginResult.STARTED, gate.begin())
        gate.failed()

        assertEquals(CameraConnectionGate.BeginResult.STARTED, gate.begin())
    }

    @Test
    fun readyConnectionIsReportedWithoutStartingAnotherAttempt() {
        val gate = CameraConnectionGate()

        gate.begin()
        gate.ready()

        assertEquals(
            CameraConnectionGate.BeginResult.ALREADY_READY,
            gate.begin(),
        )
    }
}
