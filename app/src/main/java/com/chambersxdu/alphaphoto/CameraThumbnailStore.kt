package com.chambersxdu.alphaphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal enum class ThumbnailLoadState {
    NOT_REQUESTED,
    LOADING,
    READY,
    FAILED,
}

internal fun exifRotationDegrees(orientation: Int): Float = when (orientation) {
    ExifInterface.ORIENTATION_NORMAL -> 0f
    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> error("Unsupported Sony thumbnail EXIF orientation $orientation.")
}

internal class CameraThumbnailStore(
    context: Context,
    private val ptpIpProbe: PtpIpProbe,
    associationId: Int,
) {
    private val cacheRoot = File(
        context.applicationContext.cacheDir,
        "camera-thumbnails",
    ).apply(File::mkdirs)
    private val cacheDirectory = File(
        cacheRoot,
        associationId.toString(),
    ).apply(File::mkdirs)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AlphaPhotoThumbnailDecoder")
    }
    private val states = mutableStateMapOf<Int, ThumbnailLoadState>()
    private val generation = AtomicInteger()
    private val cache = object : LruCache<Int, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    @Volatile
    private var closed = false

    fun stateFor(handle: Int): ThumbnailLoadState =
        states[handle] ?: ThumbnailLoadState.NOT_REQUESTED

    fun imageFor(handle: Int): ImageBitmap? = cache.get(handle)?.asImageBitmap()

    fun load(photo: PtpObjectInfo) {
        check(!closed)
        val requestGeneration = generation.get()
        val state = stateFor(photo.handle)
        if (state == ThumbnailLoadState.LOADING || state == ThumbnailLoadState.FAILED) {
            return
        }
        if (cache.get(photo.handle) != null) {
            states[photo.handle] = ThumbnailLoadState.READY
            return
        }

        states[photo.handle] = ThumbnailLoadState.LOADING
        val cacheFile = cacheFile(photo)
        if (cacheFile.isFile) {
            decoder.execute {
                try {
                    val bitmap = decodeThumbnail(cacheFile.readBytes())
                    cacheFile.setLastModified(System.currentTimeMillis())
                    publish(photo.handle, bitmap, requestGeneration)
                } catch (error: Throwable) {
                    try {
                        check(cacheFile.delete()) {
                            "Could not remove invalid thumbnail cache file ${cacheFile.name}."
                        }
                        requestThumbnail(photo, cacheFile, requestGeneration)
                    } catch (error: Throwable) {
                        Log.e(TAG, "Invalid thumbnail cache cleanup failed", error)
                        fail(photo.handle, requestGeneration)
                    }
                }
            }
            return
        }

        requestThumbnail(photo, cacheFile, requestGeneration)
    }

    private fun requestThumbnail(
        photo: PtpObjectInfo,
        cacheFile: File,
        requestGeneration: Int,
    ) {
        ptpIpProbe.loadThumbnail(
            photo = photo,
            onSuccess = { bytes ->
                if (closed) {
                    return@loadThumbnail
                }
                decoder.execute {
                    try {
                        val jpeg = SonyMediaProtocol.parseThumbnailJpeg(bytes)
                        val bitmap = decodeThumbnail(jpeg)
                        try {
                            writeCache(cacheFile, jpeg)
                        } catch (error: Throwable) {
                            Log.e(
                                TAG,
                                "Thumbnail cache write failed for ${photo.filename}",
                                error,
                            )
                        }
                        publish(photo.handle, bitmap, requestGeneration)
                    } catch (error: Throwable) {
                        Log.e(
                            TAG,
                            "Thumbnail decode failed for ${photo.filename}",
                            error,
                        )
                        fail(photo.handle, requestGeneration)
                    }
                }
            },
            onError = {
                if (!closed) {
                    fail(photo.handle, requestGeneration)
                }
            },
        )
    }

    fun clear() {
        generation.incrementAndGet()
        cache.evictAll()
        states.clear()
    }

    fun close() {
        closed = true
        decoder.shutdownNow()
        clear()
    }

    private fun cacheFile(photo: PtpObjectInfo): File = File(
        cacheDirectory,
        "${photo.storageId.toUInt().toString(16)}_" +
            "${photo.handle.toUInt().toString(16)}_" +
            "${photo.parentObject.toUInt().toString(16)}_" +
            "${photo.size}_${photo.thumbnailSize}_${photo.captureDate}.jpg",
    )

    private fun publish(
        handle: Int,
        bitmap: Bitmap,
        requestGeneration: Int,
    ) {
        if (closed || requestGeneration != generation.get()) {
            return
        }
        mainHandler.post {
            if (!closed && requestGeneration == generation.get()) {
                cache.put(handle, bitmap)
                states[handle] = ThumbnailLoadState.READY
            }
        }
    }

    private fun fail(
        handle: Int,
        requestGeneration: Int,
    ) {
        if (closed || requestGeneration != generation.get()) {
            return
        }
        mainHandler.post {
            if (!closed && requestGeneration == generation.get()) {
                states[handle] = ThumbnailLoadState.FAILED
            }
        }
    }

    private fun decodeThumbnail(jpeg: ByteArray): Bitmap {
        val orientation = ByteArrayInputStream(jpeg).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
        val bitmap = checkNotNull(
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size),
        ) {
            "Android could not decode the Sony thumbnail JPEG."
        }
        val rotation = exifRotationDegrees(orientation)
        if (rotation == 0f) {
            return bitmap
        }

        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(rotation) },
            true,
        )
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun writeCache(
        destination: File,
        bytes: ByteArray,
    ) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(destination)) {
            "Could not publish thumbnail cache file ${destination.name}."
        }
        pruneDiskCache()
    }

    private fun pruneDiskCache() {
        val files = cacheRoot.walkTopDown()
            .filter(File::isFile)
            .sortedBy(File::lastModified)
            .toList()
        var totalBytes = files.sumOf(File::length)

        for (file in files) {
            if (totalBytes <= DISK_CACHE_BYTES) {
                break
            }
            val size = file.length()
            check(file.delete()) {
                "Could not evict thumbnail cache file ${file.name}."
            }
            totalBytes -= size
        }
    }

    private companion object {
        const val TAG = "AlphaPhoto"
        const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
        const val DISK_CACHE_BYTES = 64L * 1024 * 1024
    }
}
