package com.chambersxdu.alphaphoto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SonyMediaProtocolTest {
    @Test
    fun parsesCapturedDates() {
        val data = writer {
            u32(2)
            u64(1_700_000_000_000)
            u64(1_700_100_000_000)
        }

        assertEquals(
            listOf(1_700_000_000_000, 1_700_100_000_000),
            SonyMediaProtocol.parseCapturedDates(data),
        )
    }

    @Test
    fun parsesStillContentMetadata() {
        val path = "/DCIM/100MSDCF/DSC00001.ARW"
        val data = writer {
            u16(1)
            bytes(byteArrayOf(0, 0))
            u64(1234)
            bytes(ByteArray(64))
            bytes(ByteArray(64))
            u32(1)
            u32(1)

            u32(1)
            u32(0x12345678)
            u32(100)
            u32(1)
            u32(0)
            u32(0)
            u32(1)
            u64(10)
            u64(11)
            u64(12)
            u64(13)
            u32(0)
            u32(0)
            u32(0)
            u32(0)
            u32(1)

            u16(0x2345)
            bytes(byteArrayOf(0, 0))
            val pathBytes =
                path.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            u32(pathBytes.size)
            bytes(pathBytes)
            u32(0xB101)
            u64(34_567_890)
            bytes(ByteArray(32))
            u32(1)
            u32(7008)
            u32(4672)
            u32(0)
            u32(0)
        }

        val parsed = SonyMediaProtocol.parseContentsInfoList(data)
        val file = parsed.files.single()

        assertEquals(1, parsed.slotId)
        assertEquals(path, file.path)
        assertEquals(0xB101, file.formatCode)
        assertEquals(34_567_890, file.size)
        assertEquals(7008, file.width)
        assertEquals(4672, file.height)
        assertEquals(0x0100234512345678L, file.uniqueId)
    }

    @Test
    fun buildsContentsInfoParameters() {
        assertEquals(
            listOf(0x55667788, 0x11223344, 60, 2, 0),
            SonyMediaProtocol.contentsInfoParams(
                captureDate = 0x1122334455667788L,
                count = 60,
                slot = 2,
            ),
        )
    }

    @Test
    fun buildsOriginalChunkParameters() {
        val file = SonyContentFile(
            slotId = 2,
            contentId = 0x11223344,
            fileId = 0x2345,
            path = "/a.ARW",
            formatCode = 0xB101,
            size = 10_000_000,
            width = 7008,
            height = 4672,
        )

        assertEquals(
            listOf(
                0x11223344,
                0x02002345,
                0x55667788,
                0x00000001,
                SonyMediaProtocol.ORIGINAL_CHUNK_SIZE,
            ),
            SonyMediaProtocol.originalChunkParams(
                file = file,
                offset = 0x0000000155667788L,
                length = SonyMediaProtocol.ORIGINAL_CHUNK_SIZE,
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

        fun u64(value: Long) {
            output.write(
                ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value)
                    .array(),
            )
        }

        fun bytes(value: ByteArray) {
            output.write(value)
        }

        fun toByteArray(): ByteArray =
            output.toByteArray()
    }
}
