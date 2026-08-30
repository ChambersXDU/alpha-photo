package com.chambersxdu.alphaphoto

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraThumbnailStoreTest {
    @Test
    fun mapsObservedSonyExifOrientationsToRotationDegrees() {
        assertEquals(0f, exifRotationDegrees(1))
        assertEquals(90f, exifRotationDegrees(6))
        assertEquals(270f, exifRotationDegrees(8))
    }
}
