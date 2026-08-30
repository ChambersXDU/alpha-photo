package com.chambersxdu.alphaphoto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PtpEventProtocolTest {
    @Test
    fun parsesSonyObjectAddedEvent() {
        val body = ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xC201.toShort())
            .putInt(-1)
            .putInt(0x400005d3)
            .array()

        assertEquals(
            PtpEvent(
                code = PtpIpProtocol.EVENT_SONY_OBJECT_ADDED,
                transactionId = -1,
                params = listOf(0x400005d3),
            ),
            PtpIpProtocol.parseEvent(body),
        )
    }

    @Test
    fun parsesSonyContentInfoListChangedEvent() {
        val body = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xC234.toShort())
            .putInt(-1)
            .putInt(1)
            .putInt(1)
            .array()

        assertEquals(
            PtpEvent(
                code = PtpIpProtocol.EVENT_SONY_CONTENT_INFO_LIST_CHANGED,
                transactionId = -1,
                params = listOf(1, 1),
            ),
            PtpIpProtocol.parseEvent(body),
        )
    }

    @Test
    fun namesSonyMediaEvents() {
        assertEquals(
            "Store Added",
            PtpIpProtocol.eventName(PtpIpProtocol.EVENT_STORE_ADDED),
        )
        assertEquals(
            "Sony Object Added",
            PtpIpProtocol.eventName(PtpIpProtocol.EVENT_SONY_OBJECT_ADDED),
        )
        assertEquals(
            "Sony Captured",
            PtpIpProtocol.eventName(PtpIpProtocol.EVENT_SONY_CAPTURED),
        )
        assertEquals(
            "Sony Content Info List Changed",
            PtpIpProtocol.eventName(
                PtpIpProtocol.EVENT_SONY_CONTENT_INFO_LIST_CHANGED,
            ),
        )
    }

    @Test
    fun describesSonyContentsTransferErrors() {
        assertEquals(
            "Device Busy",
            SonyMediaProtocol.contentsTransferEventDescription(1),
        )
        assertEquals(
            "Status Error",
            SonyMediaProtocol.contentsTransferEventDescription(2),
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
