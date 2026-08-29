package com.chambersxdu.alphaphoto

internal data class SonyContentFile(
    val slotId: Int,
    val contentId: Int,
    val fileId: Int,
    val path: String,
    val formatCode: Int,
    val size: Long,
    val width: Int?,
    val height: Int?,
) {
    val uniqueId: Long
        get() {
            val high = ((slotId and 0xFF) shl 24) or (fileId and 0xFFFF)
            return ((high.toLong() and 0xFFFFFFFFL) shl 32) or
                (contentId.toLong() and 0xFFFFFFFFL)
        }

    val name: String
        get() = path.substringAfterLast('/')
}

internal data class SonyContentsList(
    val slotId: Int,
    val files: List<SonyContentFile>,
)

internal object SonyMediaProtocol {
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_SDIO_CONNECT = 0x9201
    const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
    const val OP_SDIO_OPEN_SESSION = 0x9210
    const val OP_SDIO_SET_CONTENTS_TRANSFER_MODE = 0x9212
    const val OP_SDIO_GET_VENDOR_CODE_VERSION = 0x9216
    const val OP_SDIO_GET_CAPTURED_DATE_LIST = 0x923B
    const val OP_SDIO_GET_CONTENTS_INFO_LIST = 0x923C
    const val OP_SDIO_GET_CONTENTS_DATA = 0x923D
    const val OP_SDIO_GET_CONTENTS_COMPRESSED_DATA = 0x923E

    const val RESPONSE_OK = 0x2001
    const val RESPONSE_CAMERA_STATUS_ERROR = 0xA106
    const val FUNCTION_MODE_CONTENTS_TRANSFER = 1
    const val CONTENTS_SELECT_REMOTE_DEVICE = 2
    const val CONTENTS_TRANSFER_ON = 1
    const val CONTENTS_INFO_NONE = 0
    const val COMPRESSED_DATA_THUMBNAIL = 1
    const val COMPRESSED_DATA_SCREENNAIL = 2
    const val VENDOR_FLAG_THRESHOLD = 310
    const val ORIGINAL_CHUNK_SIZE = 3_145_728

    fun parseCapturedDates(data: ByteArray): List<Long> {
        val cursor = LittleEndianCursor(data)
        val count = cursor.u32()
        require(count <= Int.MAX_VALUE)

        return List(count.toInt()) {
            cursor.u64()
        }
    }

    fun contentsInfoParams(
        captureDate: Long,
        count: Int,
        slot: Int,
    ): List<Int> {
        require(count > 0)
        require(slot > 0)

        return listOf(
            captureDate.toInt(),
            (captureDate ushr 32).toInt(),
            count,
            slot,
            0,
        )
    }

    fun parseContentsInfoList(data: ByteArray): SonyContentsList {
        val cursor = LittleEndianCursor(data)

        cursor.u16()
        cursor.bytes(2)
        cursor.u64()
        cursor.bytes(64)
        cursor.bytes(64)
        val slotId = cursor.u32().toInt()
        val contentCount = cursor.u32()

        require(contentCount <= 4096)

        val files = buildList {
            repeat(contentCount.toInt()) {
                val contentType = cursor.u32().toInt()
                if (contentType == 0) {
                    return@buildList
                }
                require(contentType in CONTENT_TYPES) {
                    "Unknown Sony content type $contentType."
                }

                val contentId = cursor.u32().toInt()
                cursor.u32()
                cursor.u32()
                cursor.u32()
                cursor.u32()
                cursor.u32()
                cursor.u64()
                cursor.u64()
                cursor.u64()
                cursor.u64()
                cursor.u32()
                cursor.u32()
                cursor.u32()

                val shotMarkCount = cursor.u32()
                require(shotMarkCount <= Int.MAX_VALUE)
                cursor.bytes(shotMarkCount.toInt())

                val fileCount = cursor.u32()
                require(fileCount <= Int.MAX_VALUE)

                repeat(fileCount.toInt()) {
                    add(parseFile(cursor, slotId, contentId))
                }
            }
        }

        return SonyContentsList(
            slotId = slotId,
            files = files,
        )
    }

    fun compressedDataParams(
        file: SonyContentFile,
        type: Int,
    ): List<Int> {
        require(
            type == COMPRESSED_DATA_THUMBNAIL ||
                type == COMPRESSED_DATA_SCREENNAIL,
        )

        return listOf(
            file.uniqueId.toInt(),
            (file.uniqueId ushr 32).toInt(),
            type,
        )
    }

    fun originalChunkParams(
        file: SonyContentFile,
        offset: Long,
        length: Int,
    ): List<Int> {
        require(offset >= 0)
        require(length in 1..ORIGINAL_CHUNK_SIZE)

        return listOf(
            file.uniqueId.toInt(),
            (file.uniqueId ushr 32).toInt(),
            offset.toInt(),
            (offset ushr 32).toInt(),
            length,
        )
    }

    private val CONTENT_TYPES = setOf(1, 4, 8, 16)

    private fun parseFile(
        cursor: LittleEndianCursor,
        slotId: Int,
        contentId: Int,
    ): SonyContentFile {
        val fileId = cursor.u16()
        cursor.bytes(2)

        val pathLength = cursor.u32()
        require(pathLength <= Int.MAX_VALUE)
        val path = cursor.bytes(pathLength.toInt())
            .toString(Charsets.UTF_8)
            .trimEnd('\u0000')

        val formatCode = cursor.u32().toInt()
        val size = cursor.u64()
        cursor.bytes(32)

        val imageParamsPresent = cursor.u32() != 0L
        val width = if (imageParamsPresent) cursor.u32().toInt() else null
        val height = if (imageParamsPresent) cursor.u32().toInt() else null

        require(cursor.u32() == 0L) {
            "Video content parameters are not implemented."
        }
        require(cursor.u32() == 0L) {
            "Audio content parameters are not implemented."
        }

        return SonyContentFile(
            slotId = slotId,
            contentId = contentId,
            fileId = fileId,
            path = path,
            formatCode = formatCode,
            size = size,
            width = width,
            height = height,
        )
    }
}
