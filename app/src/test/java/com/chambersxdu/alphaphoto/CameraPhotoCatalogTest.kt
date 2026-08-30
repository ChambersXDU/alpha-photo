package com.chambersxdu.alphaphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CameraPhotoCatalogTest {
    @Test
    fun groupsRawAndJpegFilesIntoOneShotAndUsesJpegForPreview() {
        val olderJpeg = photo(
            handle = 1,
            filename = "DSC02291.JPG",
            captureDate = "20260823T181400",
        )
        val raw = photo(
            handle = 2,
            filename = "DSC02292.ARW",
            captureDate = "20260823T181438",
        )
        val jpeg = photo(
            handle = 3,
            filename = "DSC02292.JPG",
            captureDate = "20260823T181438",
        )

        val shots = CameraPhotoCatalog.group(listOf(olderJpeg, raw, jpeg))

        assertEquals(2, shots.size)
        assertEquals("DSC02292", shots[0].displayName)
        assertEquals(listOf(jpeg, raw), shots[0].files)
        assertSame(jpeg, shots[0].previewFile)
        assertEquals("DSC02291", shots[1].displayName)
    }

    @Test
    fun mergesNewObjectWithoutDuplicatingExistingHandle() {
        val existing = photo(
            handle = 7,
            filename = "DSC02292.JPG",
            captureDate = "20260823T181438",
        )
        val updated = existing.copy(size = 20_000_000)
        val added = photo(
            handle = 8,
            filename = "DSC02293.ARW",
            captureDate = "20260823T181500",
        )

        val merged = CameraPhotoCatalog.merge(
            current = listOf(existing),
            additions = listOf(updated, added),
        )

        assertEquals(listOf(added, updated), merged)
    }

    @Test
    fun keepsMatchingFilenamesFromDifferentFoldersSeparate() {
        val first = photo(
            handle = 9,
            filename = "DSC00001.JPG",
            captureDate = "20260823T181500",
            parentObject = 100,
        )
        val second = photo(
            handle = 10,
            filename = "DSC00001.ARW",
            captureDate = "20260823T181500",
            parentObject = 200,
        )

        assertEquals(2, CameraPhotoCatalog.group(listOf(first, second)).size)
    }

    private fun photo(
        handle: Int,
        filename: String,
        captureDate: String,
        parentObject: Int = 0,
    ) = PtpObjectInfo(
        handle = handle,
        storageId = 0x10001,
        formatCode = 0x3801,
        size = 12_000_000,
        thumbnailFormatCode = 0x3801,
        thumbnailSize = 120_000,
        thumbnailWidth = 640,
        thumbnailHeight = 424,
        width = 7008,
        height = 4672,
        parentObject = parentObject,
        associationType = 0,
        filename = filename,
        captureDate = captureDate,
    )
}
