package com.chambersxdu.alphaphoto

internal data class SonyWifiStatus(
    val state: Int,
    val error: Int,
)

internal object SonyWifiProtocol {
    fun parseStatus(value: ByteArray): SonyWifiStatus? {
        val payload = payloadForType(value, WIFI_STATUS_TYPE) ?: return null
        if (payload.size != 2) {
            return null
        }

        return SonyWifiStatus(
            state = payload[0].toInt() and 0xFF,
            error = payload[1].toInt() and 0xFF,
        )
    }

    fun parseImageTransferState(value: ByteArray): Int? {
        val payload = payloadForType(value, IMAGE_TRANSFER_TYPE) ?: return null
        if (payload.size != 1) {
            return null
        }

        return payload[0].toInt() and 0xFF
    }

    fun decodeHeaderAscii(value: ByteArray): String {
        if (value.size < 3) {
            return ""
        }

        return value.copyOfRange(3, value.size)
            .toString(Charsets.US_ASCII)
            .trimEnd('\u0000')
            .trim()
    }

    fun decodePlainAscii(value: ByteArray): String =
        value.toString(Charsets.US_ASCII)
            .trimEnd('\u0000')
            .trim()

    private fun payloadForType(
        value: ByteArray,
        requestedType: Int,
    ): ByteArray? {
        var offset = 0

        while (offset < value.size) {
            val recordLength = value[offset].toInt() and 0xFF
            if (recordLength < 2) {
                return null
            }

            val end = offset + recordLength + 1
            if (end > value.size) {
                return null
            }

            val type =
                ((value[offset + 1].toInt() and 0xFF) shl 8) or
                    (value[offset + 2].toInt() and 0xFF)

            if (type == requestedType) {
                return value.copyOfRange(offset + 3, end)
            }

            offset = end
        }

        return null
    }

    private const val WIFI_STATUS_TYPE = 1
    private const val IMAGE_TRANSFER_TYPE = 2
}
