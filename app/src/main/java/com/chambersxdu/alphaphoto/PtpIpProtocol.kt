package com.chambersxdu.alphaphoto

import java.io.InputStream
import java.io.OutputStream
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

internal data class PtpOperationTransaction(
    val response: PtpOperationResponse,
    val dataLength: Long,
)

internal data class PtpEvent(
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

    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_OBJECT_REMOVED = 0x4003
    const val EVENT_OBJECT_INFO_CHANGED = 0x4007
    const val EVENT_REQUEST_OBJECT_TRANSFER = 0x4009
    const val EVENT_CAPTURE_COMPLETE = 0x400D

    const val EVENT_SONY_OBJECT_ADDED = 0xC201
    const val EVENT_SONY_OBJECT_REMOVED = 0xC202
    const val EVENT_SONY_DEVICE_PROP_CHANGED = 0xC203
    const val EVENT_SONY_CAPTURED = 0xC206
    const val EVENT_SONY_CONTENTS_TRANSFER = 0xC20D
    const val EVENT_SONY_CONTENT_INFO_LIST_CHANGED = 0xC234

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
        val header = readHeader(input)

        return PtpIpPacket(
            type = header.type,
            body = input.readExactly(header.bodyLength),
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

    fun parseEvent(body: ByteArray): PtpEvent {
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

        return PtpEvent(
            code = code,
            transactionId = transactionId,
            params = params,
        )
    }

    fun readOperationTransaction(
        input: InputStream,
        transactionId: Int,
        output: OutputStream,
        onProbeRequest: () -> Unit,
    ): PtpOperationTransaction {
        var dataLength = 0L

        while (true) {
            val header = readHeader(input)

            when (header.type) {
                START_DATA -> {
                    val body = input.readExactly(header.bodyLength)
                    require(body.size >= 12)
                    require(
                        ByteBuffer.wrap(body, 0, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int == transactionId,
                    )
                }

                DATA,
                END_DATA,
                -> {
                    require(header.bodyLength >= 4)
                    val packetTransactionId = input.readLittleEndianInt()
                    require(packetTransactionId == transactionId)

                    val payloadLength = header.bodyLength - 4
                    input.copyExactly(
                        count = payloadLength,
                        output = output,
                    )
                    dataLength += payloadLength
                }

                OPERATION_RESPONSE -> {
                    val response = parseOperationResponse(
                        input.readExactly(header.bodyLength),
                    )
                    require(response.transactionId == transactionId)

                    return PtpOperationTransaction(
                        response = response,
                        dataLength = dataLength,
                    )
                }

                PROBE_REQUEST -> {
                    input.readExactly(header.bodyLength)
                    onProbeRequest()
                }

                PROBE_RESPONSE -> {
                    input.readExactly(header.bodyLength)
                }

                else -> error(
                    "Unexpected PTP/IP packet type ${header.type}.",
                )
            }
        }
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

    private fun readHeader(input: InputStream): PacketHeader {
        val header = input.readExactly(8)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        val type = buffer.int

        require(length >= 8)

        return PacketHeader(
            type = type,
            bodyLength = length - 8,
        )
    }

    private fun InputStream.readLittleEndianInt(): Int =
        ByteBuffer.wrap(readExactly(4))
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun InputStream.copyExactly(
        count: Int,
        output: OutputStream,
    ) {
        var remaining = count
        val buffer = ByteArray(minOf(64 * 1024, maxOf(1, count)))

        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            check(read >= 0) {
                "Connection closed while reading PTP/IP packet."
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

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

    private data class PacketHeader(
        val type: Int,
        val bodyLength: Int,
    )
}
