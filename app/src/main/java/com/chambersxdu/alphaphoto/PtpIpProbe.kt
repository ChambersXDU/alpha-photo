package com.chambersxdu.alphaphoto

import android.content.Context
import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PtpIpProbe(context: Context) {
    private val appContext = context.applicationContext

    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var transactionId = 0
    private var supportedOperations = emptySet<Int>()
    private val commandExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { task -> Thread(task, "AlphaPhotoPtpCommand") },
    )

    @Volatile
    private var onPhotoAdded: ((PtpObjectInfo) -> Unit)? = null

    @Volatile
    private var onConnectionLost: ((String) -> Unit)? = null

    @Volatile
    private var eventFailure: Throwable? = null

    @Volatile
    private var contentsTransferLatch: CountDownLatch? = null

    @Volatile
    private var contentsTransferStorageId: Int? = null

    @Volatile
    private var contentsTransferErrorId: Int? = null

    private val contentsTransferLock = ReentrantLock()
    private val contentsTransferChanged = contentsTransferLock.newCondition()
    private var contentsTransferPropertyVersion = 0L

    fun initialize(
        cameraNetwork: CameraNetwork,
        onStatus: (String) -> Unit,
        onSuccess: () -> Unit,
        onPhotoAdded: (PtpObjectInfo) -> Unit,
        onDisconnected: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        commandExecutor.execute {
            try {
                check(commandSocket == null)
                check(eventSocket == null)

                eventFailure = null
                this.onPhotoAdded = onPhotoAdded
                onConnectionLost = onDisconnected

                val host = checkNotNull(cameraNetwork.gateway.hostAddress)

                post(onStatus, "Waiting for Sony PTP/IP…")
                waitForPort(cameraNetwork.network, host)

                val command = cameraNetwork.network.socketFactory.createSocket()
                connect(command, host)
                commandSocket = command

                val guid = ByteArray(16).also(SecureRandom()::nextBytes)
                sendPacket(
                    command,
                    PtpIpProtocol.INIT_COMMAND_REQUEST,
                    PtpIpProtocol.initCommandBody(guid, "Alpha Photo"),
                )

                val commandAck = PtpIpProtocol.readPacket(command.getInputStream())
                if (commandAck.type == PtpIpProtocol.INIT_FAIL) {
                    val reason = if (commandAck.body.size >= 4) {
                        LittleEndianCursor(commandAck.body).u32()
                    } else {
                        -1
                    }
                    error("Sony rejected PTP/IP initialization. reason=$reason")
                }

                check(commandAck.type == PtpIpProtocol.INIT_COMMAND_ACK)
                val connectionNumber =
                    LittleEndianCursor(commandAck.body).u32().toInt()
                Log.i(
                    TAG,
                    "PTP/IP command channel ready connectionNumber=$connectionNumber",
                )

                val event = cameraNetwork.network.socketFactory.createSocket()
                connect(event, host)
                event.soTimeout = 0
                eventSocket = event

                sendPacket(
                    event,
                    PtpIpProtocol.INIT_EVENT_REQUEST,
                    PtpIpProtocol.littleEndianInt(connectionNumber),
                )

                val eventAck = PtpIpProtocol.readPacket(event.getInputStream())
                check(eventAck.type == PtpIpProtocol.INIT_EVENT_ACK)

                Log.i(
                    TAG,
                    "PTP/IP event channel ready host=$host port=${PtpIpProtocol.PORT}",
                )

                startEventReader(event)
                bootstrapSonySession()
                post(onStatus, "Sony transfer session ready.")
                appContext.mainExecutor.execute { onSuccess() }
            } catch (error: Throwable) {
                close()
                val message = "PTP/IP initialization failed: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }
    }

    fun listCameraPhotos(
        onStatus: (String) -> Unit,
        onSuccess: (List<PtpObjectInfo>) -> Unit,
        onError: (String) -> Unit,
    ) {
        commandExecutor.execute {
            try {
                check(
                    PtpObjectProtocol.OP_GET_STORAGE_IDS in
                        supportedOperations,
                ) {
                    "Camera does not expose standard GetStorageIDs."
                }
                check(
                    PtpObjectProtocol.OP_GET_OBJECT_HANDLES in
                        supportedOperations,
                ) {
                    "Camera does not expose standard GetObjectHandles."
                }
                check(
                    PtpObjectProtocol.OP_GET_OBJECT_INFO in
                        supportedOperations,
                ) {
                    "Camera does not expose standard GetObjectInfo."
                }

                post(onStatus, "Reading camera storage…")

                val storageIds = PtpObjectProtocol.parseStorageIds(
                    transactChecked(
                        opcode = PtpObjectProtocol.OP_GET_STORAGE_IDS,
                        name = "GetStorageIDs",
                    ).data,
                ).filter { it != 0 }

                check(storageIds.isNotEmpty()) {
                    "Camera exposed no readable storage."
                }

                Log.i(
                    TAG,
                    "PTP storage IDs=" +
                        storageIds.joinToString { id ->
                            "0x${id.toUInt().toString(16)}"
                        },
                )

                val handles = linkedSetOf<Int>()

                for (storageId in storageIds) {
                    val storageHandles = PtpObjectProtocol.parseHandles(
                        transactChecked(
                            opcode = PtpObjectProtocol.OP_GET_OBJECT_HANDLES,
                            params = listOf(
                                storageId,
                                ALL_OBJECT_FORMATS,
                                ROOT_ASSOCIATION,
                            ),
                            name =
                                "GetObjectHandles(0x${storageId.toUInt().toString(16)})",
                        ).data,
                    )

                    Log.i(
                        TAG,
                        "PTP object handles storage=0x" +
                            "${storageId.toUInt().toString(16)} " +
                            "count=${storageHandles.size}",
                    )
                    handles.addAll(storageHandles)
                }

                post(
                    onStatus,
                    "Reading metadata for ${handles.size} camera objects…",
                )

                val objects = handles.map(::readObjectInfo)
                val photos = CameraPhotoCatalog.merge(
                    current = emptyList(),
                    additions = objects.filter(PtpObjectInfo::isPhoto),
                )

                Log.i(
                    TAG,
                    "PTP camera objects count=${objects.size} photos=${photos.size}",
                )
                photos.forEach { photo ->
                    Log.i(
                        TAG,
                        "PTP photo handle=0x${photo.handle.toUInt().toString(16)} " +
                            "storage=0x${photo.storageId.toUInt().toString(16)} " +
                            "name=${photo.filename} " +
                            "format=0x${photo.formatCode.toString(16)} " +
                            "size=${photo.size} " +
                            "thumbFormat=0x${photo.thumbnailFormatCode.toString(16)} " +
                            "thumbSize=${photo.thumbnailSize} " +
                            "thumbDimensions=${photo.thumbnailWidth}x${photo.thumbnailHeight} " +
                            "dimensions=${photo.width}x${photo.height} " +
                            "captureDate=${photo.captureDate}",
                    )
                }

                probeSonyScreennail()

                post(onSuccess, photos)
            } catch (error: Throwable) {
                val message = "PTP photo listing failed: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }
    }

    private fun probeSonyScreennail() {
        try {
            check(
                SonyMediaProtocol.OP_SDIO_GET_CAPTURED_DATE_LIST in
                    supportedOperations,
            )
            check(
                SonyMediaProtocol.OP_SDIO_GET_CONTENTS_INFO_LIST in
                    supportedOperations,
            )
            check(
                SonyMediaProtocol.OP_SDIO_GET_CONTENTS_COMPRESSED_DATA in
                    supportedOperations,
            )

            val dates = SonyMediaProtocol.parseCapturedDates(
                transactChecked(
                    opcode = SonyMediaProtocol.OP_SDIO_GET_CAPTURED_DATE_LIST,
                    params = listOf(1),
                    name = "SDIO_GetCapturedDateList",
                ).data,
            )
            Log.i(TAG, "Sony screennail probe dates=${dates.size}")
            check(dates.isNotEmpty())

            val files = SonyMediaProtocol.parseContentsInfoList(
                transactChecked(
                    opcode = SonyMediaProtocol.OP_SDIO_GET_CONTENTS_INFO_LIST,
                    params = SonyMediaProtocol.contentsInfoParams(
                        captureDate = dates.max(),
                        count = 8,
                        slot = 1,
                    ),
                    name = "SDIO_GetContentsInfoList",
                ).data,
            ).files
            Log.i(TAG, "Sony screennail probe files=${files.size}")
            val file = files.first()

            val data = transactChecked(
                opcode = SonyMediaProtocol.OP_SDIO_GET_CONTENTS_COMPRESSED_DATA,
                params = SonyMediaProtocol.compressedDataParams(
                    file = file,
                    type = SonyMediaProtocol.COMPRESSED_DATA_SCREENNAIL,
                ),
                name = "SDIO_GetContentCompressedData(screennail)",
            ).data
            File(appContext.cacheDir, "sony-screennail-probe.bin")
                .writeBytes(data)
            Log.i(
                TAG,
                "Sony screennail probe name=${file.name} bytes=${data.size} head=" +
                    data.take(16).joinToString("") { byte ->
                        "%02X".format(byte)
                    },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Sony screennail probe failed: ${error.message}", error)
        }
    }

    fun loadThumbnail(
        photo: PtpObjectInfo,
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        commandExecutor.execute {
            try {
                check(PtpObjectProtocol.OP_GET_THUMB in supportedOperations) {
                    "Camera does not expose standard GetThumb."
                }

                val thumbnail = transactChecked(
                    opcode = PtpObjectProtocol.OP_GET_THUMB,
                    params = listOf(photo.handle),
                    name = "GetThumb(${photo.filename})",
                ).data

                Log.i(
                    TAG,
                    "PTP thumbnail loaded name=${photo.filename} bytes=${thumbnail.size}",
                )
                post(onSuccess, thumbnail)
            } catch (error: Throwable) {
                val message =
                    "Thumbnail failed for ${photo.filename}: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }
    }

    fun exportOriginal(
        photo: PtpObjectInfo,
        openOutput: () -> OutputStream,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        commandExecutor.execute {
            try {
                check(PtpObjectProtocol.OP_GET_OBJECT in supportedOperations) {
                    "Camera does not expose standard GetObject."
                }

                val transfer = openOutput().use { output ->
                    transactCheckedTo(
                        opcode = PtpObjectProtocol.OP_GET_OBJECT,
                        params = listOf(photo.handle),
                        name = "GetObject(${photo.filename})",
                        output = output,
                    )
                }

                check(transfer.dataLength == photo.size) {
                    "GetObject returned ${transfer.dataLength} bytes; expected ${photo.size}."
                }

                Log.i(
                    TAG,
                    "PTP original exported name=${photo.filename} bytes=${transfer.dataLength}",
                )
                appContext.mainExecutor.execute(onSuccess)
            } catch (error: Throwable) {
                val message = "Export failed for ${photo.filename}: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }
    }

    fun streamOriginal(
        file: SonyContentFile,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit,
    ) {
        var offset = 0L

        while (offset < file.size) {
            val length = minOf(
                SonyMediaProtocol.ORIGINAL_CHUNK_SIZE.toLong(),
                file.size - offset,
            ).toInt()

            val result = transactChecked(
                opcode = SonyMediaProtocol.OP_SDIO_GET_CONTENTS_DATA,
                params = SonyMediaProtocol.originalChunkParams(
                    file = file,
                    offset = offset,
                    length = length,
                ),
                name = "SDIO_GetContentsData",
            )

            check(result.data.isNotEmpty())
            output.write(result.data)
            offset += result.data.size
            onProgress(offset, file.size)
        }

        check(offset == file.size)
    }

    fun probeMediaTransfer(
        photos: List<PtpObjectInfo>,
        onStatus: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        commandExecutor.execute {
            try {
                check(PtpObjectProtocol.OP_GET_THUMB in supportedOperations) {
                    "Camera does not expose standard GetThumb."
                }
                check(PtpObjectProtocol.OP_GET_OBJECT in supportedOperations) {
                    "Camera does not expose standard GetObject."
                }

                val jpeg = photos
                    .filter { photo ->
                        val name = photo.filename.lowercase()
                        name.endsWith(".jpg") || name.endsWith(".jpeg")
                    }
                    .maxByOrNull(PtpObjectInfo::captureDate)
                val raw = photos
                    .filter { photo ->
                        val name = photo.filename.lowercase()
                        name.endsWith(".arw") || name.endsWith(".raw")
                    }
                    .maxByOrNull(PtpObjectInfo::captureDate)
                val samples = listOfNotNull(jpeg, raw)
                    .distinctBy(PtpObjectInfo::handle)

                check(samples.isNotEmpty()) {
                    "Camera listing contains no JPEG or RAW sample."
                }

                post(onStatus, "Probing thumbnails and streamed original…")

                for (photo in samples) {
                    val thumbnailResult = runCatching {
                        transactChecked(
                            opcode = PtpObjectProtocol.OP_GET_THUMB,
                            params = listOf(photo.handle),
                            name = "GetThumb(${photo.filename})",
                        ).data
                    }

                    thumbnailResult
                        .onSuccess { thumbnail ->
                            Log.i(
                                TAG,
                                "PTP thumb name=${photo.filename} " +
                                    "bytes=${thumbnail.size} " +
                                    "declaredBytes=${photo.thumbnailSize} " +
                                    "declaredDimensions=" +
                                    "${photo.thumbnailWidth}x${photo.thumbnailHeight}",
                            )
                        }
                        .onFailure { error ->
                            Log.w(
                                TAG,
                                "PTP thumb unavailable name=${photo.filename}: " +
                                    error.message,
                            )
                        }
                }

                val original = raw ?: jpeg
                checkNotNull(original)

                var originalBytes = 0L
                val sink = object : OutputStream() {
                    override fun write(value: Int) {
                        originalBytes++
                    }

                    override fun write(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        originalBytes += length
                    }
                }

                post(
                    onStatus,
                    "Streaming ${original.filename} without buffering the whole file…",
                )

                val transfer = transactCheckedTo(
                    opcode = PtpObjectProtocol.OP_GET_OBJECT,
                    params = listOf(original.handle),
                    name = "GetObject(${original.filename})",
                    output = sink,
                )

                check(originalBytes == original.size) {
                    "GetObject returned $originalBytes bytes; expected ${original.size}."
                }
                check(transfer.dataLength == original.size)

                Log.i(
                    TAG,
                    "PTP original stream name=${original.filename} " +
                        "bytes=${transfer.dataLength}",
                )

                if (
                    SonyMediaProtocol.OP_SDIO_GET_PARTIAL_LARGE_OBJECT in
                        supportedOperations
                ) {
                    for (photo in samples) {
                        val partialSize = minOf(
                            MEDIA_PROBE_PARTIAL_BYTES,
                            photo.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                        val partialOutput = ByteArrayOutputStream(partialSize)
                        val partial = transactTo(
                            opcode =
                                SonyMediaProtocol.OP_SDIO_GET_PARTIAL_LARGE_OBJECT,
                            params = SonyMediaProtocol.partialLargeObjectParams(
                                handle = photo.handle,
                                offset = 0,
                                maxBytes = partialSize,
                            ),
                            output = partialOutput,
                        )

                        if (partial.response.code == PtpResponseCodes.OK) {
                            val reportedBytes = partial.response.params
                                .firstOrNull()
                                ?.toLong()
                                ?.and(0xFFFFFFFFL)
                            check(
                                reportedBytes == null ||
                                    reportedBytes == partial.dataLength,
                            ) {
                                "Sony partial transfer reported $reportedBytes bytes " +
                                    "but delivered ${partial.dataLength}."
                            }
                            Log.i(
                                TAG,
                                "PTP Sony partial name=${photo.filename} " +
                                    "bytes=${partial.dataLength} " +
                                    "responseParams=${partial.response.params}",
                            )
                        } else {
                            Log.w(
                                TAG,
                                "PTP Sony partial unavailable name=${photo.filename} " +
                                    "response=0x" +
                                    partial.response.code.toString(16) +
                                    " (" +
                                    PtpResponseCodes.describe(
                                        partial.response.code,
                                    ) +
                                    ")",
                            )
                        }
                    }
                } else {
                    Log.i(
                        TAG,
                        "PTP Sony partial skipped: 0x9211 not advertised.",
                    )
                }

                post(
                    onSuccess,
                    "Media probe passed: streamed original completed; optional preview/range probes logged.",
                )
            } catch (error: Throwable) {
                val message = "PTP media probe failed: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }
    }

    fun close() {
        commandExecutor.queue.clear()
        contentsTransferLatch?.countDown()
        val command = commandSocket
        commandSocket = null
        val event = eventSocket
        eventSocket = null
        command?.close()
        event?.close()
        transactionId = 0
        supportedOperations = emptySet()
        eventFailure = null
        onPhotoAdded = null
        onConnectionLost = null
        contentsTransferLatch = null
        contentsTransferStorageId = null
        contentsTransferErrorId = null
        contentsTransferLock.withLock {
            contentsTransferChanged.signalAll()
        }
    }

    private fun startEventReader(socket: Socket) {
        Thread {
            try {
                while (eventSocket === socket) {
                    val packet = PtpIpProtocol.readPacket(socket.getInputStream())

                    when (packet.type) {
                        PtpIpProtocol.EVENT -> {
                            val event = PtpIpProtocol.parseEvent(packet.body)
                            Log.i(
                                TAG,
                                "PTP/IP event " +
                                    PtpIpProtocol.eventName(event.code) +
                                    " code=0x" +
                                    event.code.toString(16) +
                                    " transaction=0x" +
                                    event.transactionId.toUInt().toString(16) +
                                    " params=" +
                                    event.params.joinToString { parameter ->
                                        "0x${parameter.toUInt().toString(16)}"
                                    },
                            )
                            handleCameraEvent(event)
                        }

                        PtpIpProtocol.PROBE_REQUEST -> {
                            sendPacket(
                                socket,
                                PtpIpProtocol.PROBE_RESPONSE,
                                byteArrayOf(),
                            )
                            Log.i(TAG, "PTP/IP event ProbeRequest acknowledged")
                        }

                        PtpIpProtocol.PROBE_RESPONSE -> {
                            Log.i(TAG, "PTP/IP event ProbeResponse received")
                        }

                        else -> error(
                            "Unexpected PTP/IP event-channel packet type ${packet.type}.",
                        )
                    }
                }
            } catch (error: Throwable) {
                if (eventSocket === socket) {
                    eventFailure = error
                    Log.e(TAG, "PTP/IP event channel failed", error)
                    val callback = onConnectionLost
                    if (callback != null) {
                        post(
                            callback,
                            "PTP/IP event channel failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
            }
        }.apply {
            name = "AlphaPhotoPtpEvent"
            isDaemon = true
            start()
        }
    }

    private fun bootstrapSonySession() {
        val deviceInfo = transactChecked(
            opcode = SonyMediaProtocol.OP_GET_DEVICE_INFO,
            name = "GetDeviceInfo",
        ).data

        supportedOperations = PtpIpProtocol.parseSupportedOperations(deviceInfo)
        Log.i(TAG, "PTP supported operations count=${supportedOperations.size}")

        check(SonyMediaProtocol.OP_SDIO_OPEN_SESSION in supportedOperations) {
            "Camera does not expose Sony transfer-session operation 0x9210."
        }

        transactChecked(
            opcode = SonyMediaProtocol.OP_SDIO_OPEN_SESSION,
            params = listOf(
                1,
                SonyMediaProtocol.FUNCTION_MODE_CONTENTS_TRANSFER,
            ),
            name = "SDIO_OpenSession",
        )

        sdioConnect(1)
        sdioConnect(2)

        var vendorVersion = 0
        if (SonyMediaProtocol.OP_SDIO_GET_VENDOR_CODE_VERSION in supportedOperations) {
            val response = transactChecked(
                opcode = SonyMediaProtocol.OP_SDIO_GET_VENDOR_CODE_VERSION,
                name = "SDIO_GetVendorCodeVersion",
            )
            vendorVersion = response.params.firstOrNull() ?: 0
        }

        Log.i(TAG, "Sony vendor code version=$vendorVersion")

        val extInfoParams = if (
            vendorVersion >= SonyMediaProtocol.VENDOR_FLAG_THRESHOLD
        ) {
            listOf(300, 1)
        } else {
            listOf(300)
        }

        transactChecked(
            opcode = SonyMediaProtocol.OP_SDIO_GET_EXT_DEVICE_INFO,
            params = extInfoParams,
            name = "SDIO_GetExtDeviceInfo",
        )

        sdioConnect(3)

        check(
            SonyMediaProtocol.OP_SDIO_SET_CONTENTS_TRANSFER_MODE in
                supportedOperations,
        ) {
            "Camera does not expose Sony content-transfer operation 0x9212."
        }

        check(
            SonyMediaProtocol.OP_SDIO_GET_EXT_DEVICE_PROP in
                supportedOperations,
        ) {
            "Camera does not expose Sony property operation 0x9251."
        }

        contentsTransferStorageId = null
        contentsTransferErrorId = null
        contentsTransferLock.withLock {
            contentsTransferPropertyVersion = 0L
        }
        contentsTransferLatch = CountDownLatch(1)
        setContentsTransferMode(SonyMediaProtocol.CONTENTS_TRANSFER_ON)

        val storageId = awaitContentsTransferStorage()
        awaitContentsTransferEnabled()
        contentsTransferLatch = null

        Log.i(
            TAG,
            "Sony remote-device content transfer enabled " +
                "storage=0x${storageId.toUInt().toString(16)} property=0x1",
        )
        Log.i(TAG, "Sony PTP transfer session ready")
    }

    private fun handleCameraEvent(event: PtpEvent) {
        if (event.code == PtpIpProtocol.EVENT_STORE_ADDED) {
            val storageId = event.params.firstOrNull()
            if (storageId == null) {
                Log.e(TAG, "Store Added event did not include a storage ID.")
            } else {
                contentsTransferStorageId = storageId
                contentsTransferLatch?.countDown()
            }
            return
        }

        if (event.code == PtpIpProtocol.EVENT_SONY_CONTENTS_TRANSFER) {
            val eventId = event.params.firstOrNull()
            if (eventId != null && eventId != 0 && contentsTransferLatch != null) {
                contentsTransferErrorId = eventId
                contentsTransferLatch?.countDown()
                contentsTransferLock.withLock {
                    contentsTransferChanged.signalAll()
                }
            }
            return
        }

        if (
            event.code == PtpIpProtocol.EVENT_SONY_DEVICE_PROP_CHANGED &&
            contentsTransferLatch != null
        ) {
            contentsTransferLock.withLock {
                contentsTransferPropertyVersion++
                contentsTransferChanged.signalAll()
            }
            return
        }

        if (event.code != PtpIpProtocol.EVENT_SONY_OBJECT_ADDED) {
            return
        }

        val handle = event.params.firstOrNull()
        if (handle == null) {
            Log.e(TAG, "Sony Object Added event did not include an object handle.")
            return
        }

        commandExecutor.execute {
            try {
                val photo = readObjectInfo(handle)
                if (photo.isPhoto()) {
                    val callback = onPhotoAdded
                    if (callback != null) {
                        post(callback, photo)
                    }
                }
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "New camera object metadata failed handle=0x" +
                        handle.toUInt().toString(16),
                    error,
                )
            }
        }
    }

    private fun awaitContentsTransferStorage(): Int {
        val latch = checkNotNull(contentsTransferLatch)
        check(
            latch.await(
                CONTENTS_TRANSFER_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            ),
        ) {
            "Sony did not report Store Added after enabling content transfer."
        }

        val errorId = contentsTransferErrorId
        if (errorId != null) {
            error(
                "Sony content transfer failed: " +
                    SonyMediaProtocol.contentsTransferEventDescription(errorId),
            )
        }

        return checkNotNull(contentsTransferStorageId) {
            "Sony content transfer ended before storage became available."
        }
    }

    private fun awaitContentsTransferEnabled() {
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(CONTENTS_TRANSFER_READY_TIMEOUT_MS)
        var observedVersion = contentsTransferLock.withLock {
            contentsTransferPropertyVersion
        }

        while (true) {
            throwContentsTransferError()
            val propertyData = transactChecked(
                opcode = SonyMediaProtocol.OP_SDIO_GET_EXT_DEVICE_PROP,
                params = listOf(
                    SonyMediaProtocol.PROP_CONTENTS_TRANSFER_ENABLE_STATUS,
                ),
                name = "SDIO_GetExtDeviceProp(0xD295)",
            ).data
            Log.i(
                TAG,
                "Sony content transfer property 0xD295 raw=" +
                    propertyData.joinToString("") { byte ->
                        "%02X".format(byte)
                    },
            )
            val enabled =
                SonyMediaProtocol.parseContentsTransferEnableStatus(propertyData)
            Log.i(TAG, "Sony content transfer property 0xD295 current=$enabled")
            throwContentsTransferError()
            if (enabled == SonyMediaProtocol.CONTENTS_TRANSFER_ON) {
                return
            }
            check(enabled == SonyMediaProtocol.CONTENTS_TRANSFER_OFF) {
                "Sony content transfer property 0xD295 has unknown value $enabled."
            }

            observedVersion = awaitContentsTransferPropertyChange(
                observedVersion = observedVersion,
                deadline = deadline,
            )
        }
    }

    private fun awaitContentsTransferPropertyChange(
        observedVersion: Long,
        deadline: Long,
    ): Long = contentsTransferLock.withLock {
        while (
            contentsTransferPropertyVersion == observedVersion &&
            contentsTransferErrorId == null &&
            contentsTransferLatch != null
        ) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0L) {
                "Sony content transfer property 0xD295 did not become 1."
            }
            contentsTransferChanged.awaitNanos(remaining)
        }

        throwContentsTransferError()
        check(contentsTransferLatch != null) {
            "Sony content transfer ended before property 0xD295 became 1."
        }
        contentsTransferPropertyVersion
    }

    private fun throwContentsTransferError() {
        val errorId = contentsTransferErrorId ?: return
        error(
            "Sony content transfer failed: " +
                SonyMediaProtocol.contentsTransferEventDescription(errorId),
        )
    }

    private fun readObjectInfo(handle: Int): PtpObjectInfo {
        val result = transactChecked(
            opcode = PtpObjectProtocol.OP_GET_OBJECT_INFO,
            params = listOf(handle),
            name = "GetObjectInfo(0x${handle.toUInt().toString(16)})",
        )

        return PtpObjectProtocol.parseObjectInfo(
            handle = handle,
            data = result.data,
        )
    }

    private fun setContentsTransferMode(mode: Int) {
        transactChecked(
            opcode = SonyMediaProtocol.OP_SDIO_SET_CONTENTS_TRANSFER_MODE,
            params = listOf(
                SonyMediaProtocol.CONTENTS_SELECT_REMOTE_DEVICE,
                mode,
                SonyMediaProtocol.CONTENTS_INFO_NONE,
            ),
            name = "SDIO_SetContentsTransferMode",
        )
        Log.i(TAG, "Sony content transfer mode=$mode")
    }

    private fun sdioConnect(phase: Int) {
        transactChecked(
            opcode = SonyMediaProtocol.OP_SDIO_CONNECT,
            params = listOf(phase, 0, 0),
            name = "SDIO_Connect($phase)",
        )
        Log.i(TAG, "SDIO_Connect($phase) ok")
    }

    @Synchronized
    private fun transactChecked(
        opcode: Int,
        params: List<Int> = emptyList(),
        name: String,
    ): TransactionResult {
        val result = transact(opcode, params)
        check(result.response.code == SonyMediaProtocol.RESPONSE_OK) {
            val response = result.response.code
            val description = PtpResponseCodes.describe(response)
            "$name failed with response 0x${response.toString(16)} ($description)."
        }
        return result
    }

    @Synchronized
    private fun transactCheckedTo(
        opcode: Int,
        params: List<Int> = emptyList(),
        name: String,
        output: OutputStream,
    ): PtpOperationTransaction {
        val result = transactTo(
            opcode = opcode,
            params = params,
            output = output,
        )
        check(result.response.code == SonyMediaProtocol.RESPONSE_OK) {
            val response = result.response.code
            val description = PtpResponseCodes.describe(response)
            "$name failed with response 0x${response.toString(16)} ($description)."
        }
        return result
    }

    private fun transact(
        opcode: Int,
        params: List<Int>,
    ): TransactionResult {
        val output = ByteArrayOutputStream()
        val result = transactTo(
            opcode = opcode,
            params = params,
            output = output,
        )

        return TransactionResult(
            response = result.response,
            data = output.toByteArray(),
            params = result.response.params,
        )
    }

    private fun transactTo(
        opcode: Int,
        params: List<Int>,
        output: OutputStream,
    ): PtpOperationTransaction {
        val failure = eventFailure
        check(failure == null) {
            "PTP/IP event channel failed: ${failure?.message}"
        }

        val socket = checkNotNull(commandSocket)
        val currentTransactionId = transactionId++

        sendPacket(
            socket,
            PtpIpProtocol.OPERATION_REQUEST,
            PtpIpProtocol.operationRequestBody(
                phase = PtpIpProtocol.PHASE_NO_DATA_OR_DATA_IN,
                opcode = opcode,
                transactionId = currentTransactionId,
                params = params,
            ),
        )

        return PtpIpProtocol.readOperationTransaction(
            input = socket.getInputStream(),
            transactionId = currentTransactionId,
            output = output,
            onProbeRequest = {
                sendPacket(
                    socket,
                    PtpIpProtocol.PROBE_RESPONSE,
                    byteArrayOf(),
                )
                Log.i(TAG, "PTP/IP command ProbeRequest acknowledged")
            },
        )
    }

    private fun waitForPort(
        network: Network,
        host: String,
    ) {
        val deadline = System.currentTimeMillis() + PORT_WAIT_TIMEOUT_MS
        var lastError: Throwable? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host, PtpIpProtocol.PORT),
                        SOCKET_CONNECT_TIMEOUT_MS,
                    )
                    Log.i(TAG, "Sony PTP/IP port reachable host=$host")
                    return
                }
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(PORT_RETRY_DELAY_MS)
            }
        }

        error(
            "Sony PTP/IP port did not become reachable: ${lastError?.message}",
        )
    }

    private fun connect(
        socket: Socket,
        host: String,
    ) {
        socket.connect(
            InetSocketAddress(host, PtpIpProtocol.PORT),
            SOCKET_CONNECT_TIMEOUT_MS,
        )
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        socket.tcpNoDelay = true
    }

    private fun sendPacket(
        socket: Socket,
        type: Int,
        body: ByteArray,
    ) {
        socket.getOutputStream().write(
            PtpIpProtocol.encodePacket(type, body),
        )
        socket.getOutputStream().flush()
    }

    private fun <T> post(
        callback: (T) -> Unit,
        value: T,
    ) {
        appContext.mainExecutor.execute {
            callback(value)
        }
    }

    private data class TransactionResult(
        val response: PtpOperationResponse,
        val data: ByteArray,
        val params: List<Int>,
    )

    private companion object {
        const val TAG = "AlphaPhoto"
        const val ALL_OBJECT_FORMATS = 0
        const val ROOT_ASSOCIATION = 0
        const val CONTENTS_TRANSFER_READY_TIMEOUT_MS = 15_000L
        const val MEDIA_PROBE_PARTIAL_BYTES = 1024 * 1024

        const val SOCKET_CONNECT_TIMEOUT_MS = 2_000
        const val SOCKET_READ_TIMEOUT_MS = 15_000
        const val PORT_WAIT_TIMEOUT_MS = 30_000L
        const val PORT_RETRY_DELAY_MS = 1_000L
    }
}
