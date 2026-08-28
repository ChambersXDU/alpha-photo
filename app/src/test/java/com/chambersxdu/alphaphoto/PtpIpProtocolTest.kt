package com.chambersxdu.alphaphoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpIpProtocolTest {
    @Test
    fun encodesPacketHeaderLittleEndian() {
        assertEquals(
            "0b00000006000000010203",
            PtpIpProtocol.encodePacket(
                type = PtpIpProtocol.OPERATION_REQUEST,
                body = byteArrayOf(1, 2, 3),
            ).toHex(),
        )
    }

    @Test
    fun readsPacketFromStream() {
        val encoded = PtpIpProtocol.encodePacket(
            type = PtpIpProtocol.END_DATA,
            body = byteArrayOf(4, 5, 6),
        )

        val packet =
            PtpIpProtocol.readPacket(ByteArrayInputStream(encoded))

        assertEquals(PtpIpProtocol.END_DATA, packet.type)
        assertArrayEquals(byteArrayOf(4, 5, 6), packet.body)
    }

    @Test
    fun buildsInitCommandBody() {
        val guid = ByteArray(16) { it.toByte() }

        assertEquals(
            "000102030405060708090a0b0c0d0e0f4100000000000100",
            PtpIpProtocol.initCommandBody(guid, "A").toHex(),
        )
    }

    @Test
    fun buildsOperationRequestBody() {
        assertEquals(
            "010000003c920700000044332211ffffffff",
            PtpIpProtocol.operationRequestBody(
                phase = PtpIpProtocol.PHASE_NO_DATA_OR_DATA_IN,
                opcode = SonyMediaProtocol.OP_SDIO_GET_CONTENTS_INFO_LIST,
                transactionId = 7,
                params = listOf(0x11223344, -1),
            ).toHex(),
        )
    }

    @Test
    fun parsesOperationResponse() {
        val body = ByteArrayOutputStream().apply {
            writeU16(0x2001)
            writeU32(7)
            writeU32(310)
        }.toByteArray()

        assertEquals(
            PtpOperationResponse(
                code = 0x2001,
                transactionId = 7,
                params = listOf(310),
            ),
            PtpIpProtocol.parseOperationResponse(body),
        )
    }

    @Test
    fun extractsDataPayloadForTransaction() {
        assertArrayEquals(
            byteArrayOf(9, 8, 7),
            PtpIpProtocol.dataPayload(
                body = PtpIpProtocol.littleEndianInt(4) +
                    byteArrayOf(9, 8, 7),
                transactionId = 4,
            ),
        )
    }

    @Test
    fun parsesSupportedOperationsFromDeviceInfo() {
        val data = ByteArrayOutputStream().apply {
            writeU16(100)
            writeU32(0)
            writeU16(100)
            writePtpString("")
            writeU16(0)
            writeU32(3)
            writeU16(0x1001)
            writeU16(0x9210)
            writeU16(0x923C)
        }.toByteArray()

        val operations =
            PtpIpProtocol.parseSupportedOperations(data)

        assertEquals(3, operations.size)
        assertTrue(0x1001 in operations)
        assertTrue(0x9210 in operations)
        assertTrue(0x923C in operations)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(
            ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(value.toShort())
                .array(),
        )
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write(
            ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array(),
        )
    }

    private fun ByteArrayOutputStream.writePtpString(value: String) {
        if (value.isEmpty()) {
            write(0)
            return
        }

        val encoded = value.toByteArray(Charsets.UTF_16LE)
        write(value.length + 1)
        write(encoded)
        write(byteArrayOf(0, 0))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
