package com.chambersxdu.alphaphoto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

internal class PhotoExportManager(
    context: Context,
    private val ptpIpProbe: PtpIpProbe,
) {
    private val resolver = context.applicationContext.contentResolver

    fun export(
        photo: PtpObjectInfo,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit,
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(photo.filename))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Alpha Photo",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            )
        } catch (error: Throwable) {
            onError(
                "Android could not create the export destination: " +
                    (error.message ?: error.javaClass.simpleName),
            )
            return
        }

        if (uri == null) {
            onError("Android could not create the export destination.")
            return
        }

        ptpIpProbe.exportOriginal(
            photo = photo,
            openOutput = {
                checkNotNull(resolver.openOutputStream(uri, "w"))
            },
            onSuccess = {
                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                try {
                    check(resolver.update(uri, completed, null, null) == 1) {
                        "Android did not publish the exported file."
                    }
                    onSuccess(uri)
                } catch (error: Throwable) {
                    val cleanupError = deletePending(uri)
                    onError(
                        "Android could not publish the exported file: " +
                            (error.message ?: error.javaClass.simpleName) +
                            cleanupError,
                    )
                }
            },
            onError = { message ->
                onError(message + deletePending(uri))
            },
        )
    }

    private fun deletePending(uri: Uri): String = try {
        resolver.delete(uri, null, null)
        ""
    } catch (error: Throwable) {
        " Cleanup also failed: " +
            (error.message ?: error.javaClass.simpleName)
    }

    private fun mimeType(filename: String): String =
        when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "arw" -> "image/x-sony-arw"
            "heif", "hif" -> "image/heif"
            else -> "application/octet-stream"
        }
}
