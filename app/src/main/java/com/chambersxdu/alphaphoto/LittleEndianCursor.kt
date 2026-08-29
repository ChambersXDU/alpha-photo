package com.chambersxdu.alphaphoto

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class LittleEndianCursor(
    private val data: ByteArray,
) {
    private var offset = 0

    fun u8(): Int =
        take(1)[0].toInt() and 0xFF

    fun u16(): Int =
        ByteBuffer.wrap(take(2))
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF

    fun u32(): Long =
        ByteBuffer.wrap(take(4))
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFFFFFFL

    fun u64(): Long =
        ByteBuffer.wrap(take(8))
            .order(ByteOrder.LITTLE_ENDIAN)
            .long

    fun bytes(count: Int): ByteArray =
        take(count)

    fun ptpString(): String {
        val characterCount = u8()
        if (characterCount == 0) {
            return ""
        }

        val raw = take(characterCount * 2)
        return raw
            .copyOfRange(0, raw.size - 2)
            .toString(Charsets.UTF_16LE)
    }

    fun u16Array(): List<Int> {
        val count = u32()
        require(count <= Int.MAX_VALUE)

        return List(count.toInt()) {
            u16()
        }
    }

    fun u32Array(): List<Int> {
        val count = u32()
        require(count <= Int.MAX_VALUE)

        return List(count.toInt()) {
            u32().toInt()
        }
    }

    private fun take(count: Int): ByteArray {
        require(count >= 0)
        require(offset + count <= data.size) {
            "Dataset ended at offset $offset while reading $count bytes."
        }

        val result = data.copyOfRange(offset, offset + count)
        offset += count
        return result
    }
}
