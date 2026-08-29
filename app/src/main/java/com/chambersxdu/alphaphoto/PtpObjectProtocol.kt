package com.chambersxdu.alphaphoto

internal data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val formatCode: Int,
    val size: Long,
    val width: Int,
    val height: Int,
    val filename: String,
    val captureDate: String,
)

internal object PtpObjectProtocol {
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008

    const val FORMAT_JPEG = 0x3801
    const val FORMAT_RAW = 0xB101
    const val FORMAT_HEIF = 0xB110

    val PHOTO_FORMATS = listOf(
        FORMAT_JPEG,
        FORMAT_RAW,
        FORMAT_HEIF,
    )

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

        cursor.u16()
        cursor.u32()
        cursor.u32()
        cursor.u32()

        val width = cursor.u32().toInt()
        val height = cursor.u32().toInt()

        cursor.u32()
        cursor.u32()
        cursor.u16()
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
            width = width,
            height = height,
            filename = filename,
            captureDate = captureDate,
        )
    }
}
