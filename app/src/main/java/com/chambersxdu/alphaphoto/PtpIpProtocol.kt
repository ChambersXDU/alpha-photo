package com.chambersxdu.alphaphoto

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PtpIpPacket(
    val type: Int,
    val body: ByteArray,
)

internal data class PtpOperationResponse(
    val code: Int,
    val transactionId: Int,
    val params: List<Int>,
)

internal object PtpIpProtocol {
    const val PORT = 15740
    const val VERSION = 0x00010000

    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14

    const val PHASE_NO_DATA_OR_DATA_IN = 1

    fun encodePacket(
        type: Int,
        body: ByteArray = byteArrayOf(),
    ): ByteArray =
        ByteBuffer.allocate(body.size + 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(body.size + 8)
            .putInt(type)
            .put(body)
            .array()

    fun readPacket(input: InputStream): PtpIpPacket {
        val header = input.readExactly(8)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        val type = buffer.int

        require(length >= 8)

        return PtpIpPacket(
            type = type,
            body = input.readExactly(length - 8),
        )
    }

    fun initCommandBody(
        guid: ByteArray,
        clientName: String,
    ): ByteArray {
        require(guid.size == 16)
        require(clientName.length <= 39)

        return guid +
            clientName.toByteArray(Charsets.UTF_16LE) +
            byteArrayOf(0x00, 0x00) +
            littleEndianInt(VERSION)
    }

    fun operationRequestBody(
        phase: Int,
        opcode: Int,
        transactionId: Int,
        params: List<Int> = emptyList(),
    ): ByteArray {
        val buffer = ByteBuffer.allocate(10 + params.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(phase)
        buffer.putShort((opcode and 0xFFFF).toShort())
        buffer.putInt(transactionId)
        params.forEach(buffer::putInt)

        return buffer.array()
    }

    fun parseOperationResponse(body: ByteArray): PtpOperationResponse {
        require(body.size >= 6)
        require((body.size - 6) % 4 == 0)

        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val code = buffer.short.toInt() and 0xFFFF
        val transactionId = buffer.int
        val params = buildList {
            while (buffer.remaining() >= 4) {
                add(buffer.int)
            }
        }

        return PtpOperationResponse(
            code = code,
            transactionId = transactionId,
            params = params,
        )
    }

    fun dataPayload(
        body: ByteArray,
        transactionId: Int,
    ): ByteArray {
        require(body.size >= 4)

        val packetTransactionId = ByteBuffer.wrap(body, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        require(packetTransactionId == transactionId)

        return body.copyOfRange(4, body.size)
    }

    fun parseSupportedOperations(deviceInfo: ByteArray): Set<Int> {
        val cursor = LittleEndianCursor(deviceInfo)

        cursor.u16()
        cursor.u32()
        cursor.u16()
        cursor.ptpString()
        cursor.u16()

        return cursor.u16Array().toSet()
    }

    fun littleEndianInt(value: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun InputStream.readExactly(count: Int): ByteArray {
        val output = ByteArray(count)
        var offset = 0

        while (offset < count) {
            val read = read(output, offset, count - offset)
            check(read >= 0) {
                "Connection closed while reading PTP/IP packet."
            }
            offset += read
        }

        return output
    }
}
