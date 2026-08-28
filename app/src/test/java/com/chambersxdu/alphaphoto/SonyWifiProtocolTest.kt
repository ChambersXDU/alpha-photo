package com.chambersxdu.alphaphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonyWifiProtocolTest {
    @Test
    fun parsesLaunchingStatus() {
        assertEquals(
            SonyWifiStatus(state = 1, error = 0),
            SonyWifiProtocol.parseStatus(hex("0400010100")),
        )
    }

    @Test
    fun parsesLaunchedStatus() {
        assertEquals(
            SonyWifiStatus(state = 2, error = 0),
            SonyWifiProtocol.parseStatus(hex("0400010200")),
        )
    }

    @Test
    fun parsesWifiError() {
        assertEquals(
            SonyWifiStatus(state = 2, error = 5),
            SonyWifiProtocol.parseStatus(hex("0400010205")),
        )
    }

    @Test
    fun parsesImageTransferStateFromCombinedStatus() {
        assertEquals(
            1,
            SonyWifiProtocol.parseImageTransferState(
                hex("040001020003000201"),
            ),
        )
    }

    @Test
    fun parsesWifiStatusAfterOtherRecord() {
        assertEquals(
            SonyWifiStatus(state = 2, error = 0),
            SonyWifiProtocol.parseStatus(
                hex("030002010400010200"),
            ),
        )
    }

    @Test
    fun rejectsTruncatedStatus() {
        assertNull(SonyWifiProtocol.parseStatus(hex("04000102")))
    }

    @Test
    fun decodesHeaderPrefixedAscii() {
        val payload = byteArrayOf(0x08, 0x00, 0x00) +
            "abcdefgh".toByteArray(Charsets.US_ASCII)

        assertEquals(
            "abcdefgh",
            SonyWifiProtocol.decodeHeaderAscii(payload),
        )
    }

    @Test
    fun decodesPlainAscii() {
        assertEquals(
            "36:90:ea:ea:41:21",
            SonyWifiProtocol.decodePlainAscii(
                "36:90:ea:ea:41:21".toByteArray(Charsets.US_ASCII),
            ),
        )
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()
}
