package com.chambersxdu.alphaphoto

internal object PtpResponseCodes {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val INCOMPLETE_TRANSFER = 0x2007
    const val INVALID_STORAGE_ID = 0x2008
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val INVALID_OBJECT_FORMAT_CODE = 0x200B
    const val STORE_NOT_AVAILABLE = 0x2013
    const val DEVICE_BUSY = 0x2019
    const val TRANSACTION_CANCELED = 0x201F

    fun describe(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "General Error"
        INCOMPLETE_TRANSFER -> "Incomplete Transfer"
        INVALID_STORAGE_ID -> "Invalid Storage ID"
        INVALID_OBJECT_HANDLE -> "Invalid Object Handle"
        DEVICE_PROP_NOT_SUPPORTED -> "Device Property Not Supported"
        INVALID_OBJECT_FORMAT_CODE -> "Invalid Object Format Code"
        STORE_NOT_AVAILABLE -> "Store Not Available"
        DEVICE_BUSY -> "Device Busy"
        TRANSACTION_CANCELED -> "Transaction Canceled"
        0xA101 -> "Authentication Failed"
        0xA102 -> "Password Length Over Max"
        0xA103 -> "Password Includes Invalid Character"
        0xA104 -> "Feature Version Invalid Value"
        0xA105 -> "Temporary Storage Full"
        0xA106 -> "Camera Status Error"
        else -> "Unknown response 0x${code.toString(16)}"
    }
}
