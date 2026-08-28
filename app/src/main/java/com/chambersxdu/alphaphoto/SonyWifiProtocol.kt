package com.chambersxdu.alphaphoto

internal data class SonyWifiStatus(
    val state: Int,
    val error: Int,
)

internal object SonyWifiProtocol {
    fun parseStatus(value: ByteArray): SonyWifiStatus? {
        var offset = 0

        while (offset + 3 < value.size) {
            val type =
                ((value[offset + 1].toInt() and 0xFF) shl 8) or
                    (value[offset + 2].toInt() and 0xFF)

            when (type) {
                1 -> {
                    if (offset + 4 >= value.size) {
                        return null
                    }

                    return SonyWifiStatus(
                        state = value[offset + 3].toInt() and 0xFF,
                        error = value[offset + 4].toInt() and 0xFF,
                    )
                }

                4 -> offset += 6
                2, 3, 5, 6, 7, 8, 9, 10 -> offset += 4
                else -> offset += (value[0].toInt() and 0xFF) + 1
            }
        }

        return null
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
}
