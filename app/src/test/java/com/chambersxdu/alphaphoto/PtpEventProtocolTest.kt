package com.chambersxdu.alphaphoto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PtpEventProtocolTest {
    @Test
    fun parsesObjectAddedEvent() {
        val body = ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x4002.toShort())
            .putInt(-1)
            .putInt(0x400005d3)
            .array()

        assertEquals(
            PtpEvent(
                code = PtpIpProtocol.EVENT_OBJECT_ADDED,
                transactionId = -1,
                params = listOf(0x400005d3),
            ),
            PtpIpProtocol.parseEvent(body),
        )
    }

    @Test
    fun parsesEventWithoutParameters() {
        val body = ByteBuffer.allocate(6)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x400D.toShort())
            .putInt(42)
            .array()

        assertEquals(
            PtpEvent(
                code = PtpIpProtocol.EVENT_CAPTURE_COMPLETE,
                transactionId = 42,
                params = emptyList(),
            ),
            PtpIpProtocol.parseEvent(body),
        )
    }
}
