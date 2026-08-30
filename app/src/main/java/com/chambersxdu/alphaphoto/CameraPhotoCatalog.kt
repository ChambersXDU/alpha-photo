package com.chambersxdu.alphaphoto

internal data class CameraShot(
    val displayName: String,
    val captureDate: String,
    val files: List<PtpObjectInfo>,
) {
    val previewFile: PtpObjectInfo =
        files.firstOrNull { file ->
            val extension = file.filename.substringAfterLast('.').lowercase()
            extension == "jpg" || extension == "jpeg"
        } ?: files.first()

    val rawFile: PtpObjectInfo? =
        files.firstOrNull { file ->
            val extension = file.filename.substringAfterLast('.').lowercase()
            extension == "arw" || extension == "raw"
        }

    val jpegFile: PtpObjectInfo? =
        files.firstOrNull { file ->
            val extension = file.filename.substringAfterLast('.').lowercase()
            extension == "jpg" || extension == "jpeg"
        }
}

internal object CameraPhotoCatalog {
    fun group(photos: List<PtpObjectInfo>): List<CameraShot> =
        photos
            .groupBy { photo ->
                PhotoKey(
                    storageId = photo.storageId,
                    parentObject = photo.parentObject,
                    basename = photo.filename.substringBeforeLast('.'),
                )
            }
            .map { (key, files) ->
                val sortedFiles = files.sortedBy { file ->
                    when (file.filename.substringAfterLast('.').lowercase()) {
                        "jpg", "jpeg" -> 0
                        "arw", "raw" -> 1
                        else -> 2
                    }
                }

                CameraShot(
                    displayName = key.basename,
                    captureDate = sortedFiles.maxOf(PtpObjectInfo::captureDate),
                    files = sortedFiles,
                )
            }
            .sortedWith(
                compareByDescending<CameraShot>(CameraShot::captureDate)
                    .thenByDescending(CameraShot::displayName),
            )

    fun merge(
        current: List<PtpObjectInfo>,
        additions: List<PtpObjectInfo>,
    ): List<PtpObjectInfo> =
        (current + additions)
            .associateBy(PtpObjectInfo::handle)
            .values
            .sortedWith(
                compareByDescending<PtpObjectInfo>(PtpObjectInfo::captureDate)
                    .thenByDescending(PtpObjectInfo::filename),
            )

    private data class PhotoKey(
        val storageId: Int,
        val parentObject: Int,
        val basename: String,
    )
}
