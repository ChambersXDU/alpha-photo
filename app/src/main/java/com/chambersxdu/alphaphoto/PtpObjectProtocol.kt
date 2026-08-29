package com.chambersxdu.alphaphoto

internal data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val formatCode: Int,
    val size: Long,
    val thumbnailFormatCode: Int = 0,
    val thumbnailSize: Long = 0,
    val thumbnailWidth: Int = 0,
    val thumbnailHeight: Int = 0,
    val width: Int,
    val height: Int,
    val associationType: Int,
    val filename: String,
    val captureDate: String,
) {
    fun isPhoto(): Boolean {
        val lower = filename.lowercase()
        return associationType == 0 &&
            size > 0 &&
            (
                lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") ||
                    lower.endsWith(".arw") ||
                    lower.endsWith(".raw") ||
                    lower.endsWith(".heif") ||
                    lower.endsWith(".hif")
            )
    }
}

internal object PtpObjectProtocol {
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    fun parseStorageIds(data: ByteArray): List<Int> =
        LittleEndianCursor(data).u32Array()

    fun parseHandles(data: ByteArray): List<Int> =
        LittleEndianCursor(data).u32Array()

    fun parseObjectInfo(
        handle: Int,
        data: ByteArray,
    ): PtpObjectInfo {
        val cursor = LittleEndianCursor(data)

        val storageId = cursor.u32().toInt()
        val formatCode = cursor.u16()
        cursor.u16()
        val size = cursor.u32()

        val thumbnailFormatCode = cursor.u16()
        val thumbnailSize = cursor.u32()
        val thumbnailWidth = cursor.u32().toInt()
        val thumbnailHeight = cursor.u32().toInt()

        val width = cursor.u32().toInt()
        val height = cursor.u32().toInt()

        cursor.u32()
        cursor.u32()
        val associationType = cursor.u16()
        cursor.u32()
        cursor.u32()

        val filename = cursor.ptpString()
        val captureDate = cursor.ptpString()
        cursor.ptpString()
        cursor.ptpString()

        return PtpObjectInfo(
            handle = handle,
            storageId = storageId,
            formatCode = formatCode,
            size = size,
            thumbnailFormatCode = thumbnailFormatCode,
            thumbnailSize = thumbnailSize,
            thumbnailWidth = thumbnailWidth,
            thumbnailHeight = thumbnailHeight,
            width = width,
            height = height,
            associationType = associationType,
            filename = filename,
            captureDate = captureDate,
        )
    }
}
