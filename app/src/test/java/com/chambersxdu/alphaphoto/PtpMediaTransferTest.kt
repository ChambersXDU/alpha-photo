package com.chambersxdu.alphaphoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PtpMediaTransferTest {
    @Test
    fun streamsDataPacketsDirectlyToOutput() {
        val transactionId = 7
        val input = ByteArrayInputStream(
            PtpIpProtocol.encodePacket(
                type = PtpIpProtocol.START_DATA,
                body = PtpIpProtocol.littleEndianInt(transactionId) +
                    littleEndianLong(5),
            ) +
                PtpIpProtocol.encodePacket(
                    type = PtpIpProtocol.DATA,
                    body = PtpIpProtocol.littleEndianInt(transactionId) +
                        byteArrayOf(1, 2),
                ) +
                PtpIpProtocol.encodePacket(
                    type = PtpIpProtocol.PROBE_REQUEST,
                ) +
                PtpIpProtocol.encodePacket(
                    type = PtpIpProtocol.END_DATA,
                    body = PtpIpProtocol.littleEndianInt(transactionId) +
                        byteArrayOf(3, 4, 5),
                ) +
                PtpIpProtocol.encodePacket(
                    type = PtpIpProtocol.OPERATION_RESPONSE,
                    body = operationResponse(
                        code = 0x2001,
                        transactionId = transactionId,
                        params = listOf(5),
                    ),
                ),
        )
        val output = ByteArrayOutputStream()
        var probeRequests = 0

        val result = PtpIpProtocol.readOperationTransaction(
            input = input,
            transactionId = transactionId,
            output = output,
            onProbeRequest = { probeRequests++ },
        )

        assertEquals(5L, result.dataLength)
        assertEquals(1, probeRequests)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), output.toByteArray())
        assertEquals(
            PtpOperationResponse(
                code = 0x2001,
                transactionId = transactionId,
                params = listOf(5),
            ),
            result.response,
        )
    }

    private fun littleEndianLong(value: Long): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
            .array()

    private fun operationResponse(
        code: Int,
        transactionId: Int,
        params: List<Int>,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(6 + params.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(code.toShort())
        buffer.putInt(transactionId)
        params.forEach(buffer::putInt)
        return buffer.array()
    }
}
