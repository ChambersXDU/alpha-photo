package com.chambersxdu.alphaphoto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PtpObjectProtocolTest {
    @Test
    fun parsesObjectHandles() {
        val data = writer {
            u32(3)
            u32(0x1001)
            u32(0x1002)
            u32(0x1003)
        }

        assertEquals(
            listOf(0x1001, 0x1002, 0x1003),
            PtpObjectProtocol.parseHandles(data),
        )
    }

    @Test
    fun parsesRawObjectInfo() {
        val data = writer {
            u32(0x00010001)
            u16(PtpObjectProtocol.FORMAT_RAW)
            u16(0)
            u32(38_547_456)

            u16(PtpObjectProtocol.FORMAT_JPEG)
            u32(6_789)
            u32(160)
            u32(120)

            u32(7008)
            u32(4672)
            u32(14)
            u32(0)
            u16(0)
            u32(0)
            u32(42)

            ptpString("DSC00001.ARW")
            ptpString("20260829T084500")
            ptpString("20260829T084501")
            ptpString("")
        }

        assertEquals(
            PtpObjectInfo(
                handle = 0x1234,
                storageId = 0x00010001,
                formatCode = PtpObjectProtocol.FORMAT_RAW,
                size = 38_547_456,
                width = 7008,
                height = 4672,
                filename = "DSC00001.ARW",
                captureDate = "20260829T084500",
            ),
            PtpObjectProtocol.parseObjectInfo(
                handle = 0x1234,
                data = data,
            ),
        )
    }

    private fun writer(block: Writer.() -> Unit): ByteArray =
        Writer().apply(block).toByteArray()

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun u16(value: Int) {
            output.write(
                ByteBuffer.allocate(2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(value.toShort())
                    .array(),
            )
        }

        fun u32(value: Int) {
            output.write(
                ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value)
                    .array(),
            )
        }

        fun ptpString(value: String) {
            if (value.isEmpty()) {
                output.write(0)
                return
            }

            val encoded = value.toByteArray(Charsets.UTF_16LE)
            output.write(value.length + 1)
            output.write(encoded)
            output.write(byteArrayOf(0, 0))
        }

        fun toByteArray(): ByteArray =
            output.toByteArray()
    }
}
