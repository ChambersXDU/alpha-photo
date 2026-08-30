package com.chambersxdu.alphaphoto

import org.junit.Assert.assertEquals
import org.junit.Test

class PtpResponseCodeTest {
    @Test
    fun describesKnownStandardAndSonyCodes() {
        assertEquals("OK", PtpResponseCodes.describe(0x2001))
        assertEquals("Invalid Storage ID", PtpResponseCodes.describe(0x2008))
        assertEquals("Invalid Object Handle", PtpResponseCodes.describe(0x2009))
        assertEquals("Store Not Available", PtpResponseCodes.describe(0x2013))
        assertEquals("Device Busy", PtpResponseCodes.describe(0x2019))
        assertEquals("Camera Status Error", PtpResponseCodes.describe(0xA106))
    }

    @Test
    fun formatsUnknownCodesWithoutLosingTheValue() {
        assertEquals("Unknown response 0x2abc", PtpResponseCodes.describe(0x2ABC))
    }
}
