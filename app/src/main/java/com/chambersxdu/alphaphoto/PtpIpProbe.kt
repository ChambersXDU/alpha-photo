package com.chambersxdu.alphaphoto

import android.content.Context
import android.net.Network
import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

internal class PtpIpProbe(context: Context) {
    private val appContext = context.applicationContext

    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var transactionId = 0
    private var supportedOperations = emptySet<Int>()

    @Volatile
    private var closed = true

    @Volatile
    private var eventFailure: Throwable? = null

    fun initialize(
        cameraNetwork: CameraNetwork,
        onStatus: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                check(commandSocket == null)
                check(eventSocket == null)

                closed = false
                eventFailure = null

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
        }.start()
    }

    fun listCameraPhotos(
        onStatus: (String) -> Unit,
        onSuccess: (List<PtpObjectInfo>) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
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

                val objects = handles.map { handle ->
                    val result = transactChecked(
                        opcode = PtpObjectProtocol.OP_GET_OBJECT_INFO,
                        params = listOf(handle),
                        name =
                            "GetObjectInfo(0x${handle.toUInt().toString(16)})",
                    )

                    PtpObjectProtocol.parseObjectInfo(
                        handle = handle,
                        data = result.data,
                    )
                }

                val photos = objects.filter(PtpObjectInfo::isPhoto)

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
                            "dimensions=${photo.width}x${photo.height} " +
                            "captureDate=${photo.captureDate}",
                    )
                }

                post(onSuccess, photos)
            } catch (error: Throwable) {
                val message = "PTP photo listing failed: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }.start()
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

    fun close() {
        closed = true
        commandSocket?.close()
        commandSocket = null
        eventSocket?.close()
        eventSocket = null
        transactionId = 0
        supportedOperations = emptySet()
        eventFailure = null
    }

    private fun startEventReader(socket: Socket) {
        Thread {
            try {
                while (!closed) {
                    val packet = PtpIpProtocol.readPacket(socket.getInputStream())

                    when (packet.type) {
                        PtpIpProtocol.EVENT -> {
                            Log.i(
                                TAG,
                                "PTP/IP event received bytes=${packet.body.size}",
                            )
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
                if (!closed) {
                    eventFailure = error
                    Log.e(TAG, "PTP/IP event channel failed", error)
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

        setContentsTransferMode(SonyMediaProtocol.CONTENTS_TRANSFER_OFF)
        Thread.sleep(CONTENTS_TRANSFER_RESET_MS)
        setContentsTransferMode(SonyMediaProtocol.CONTENTS_TRANSFER_ON)
        Thread.sleep(CONTENTS_TRANSFER_SETTLE_MS)

        Log.i(TAG, "Sony remote-device content transfer enabled")
        Log.i(TAG, "Sony PTP transfer session ready")
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
            val description = when (response) {
                SonyMediaProtocol.RESPONSE_CAMERA_STATUS_ERROR ->
                    "Camera Status Error"
                else -> "Unknown"
            }
            "$name failed with response 0x${response.toString(16)} ($description)."
        }
        return result
    }

    private fun transact(
        opcode: Int,
        params: List<Int>,
    ): TransactionResult {
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

        val data = ArrayList<ByteArray>()

        while (true) {
            val packet = PtpIpProtocol.readPacket(socket.getInputStream())

            when (packet.type) {
                PtpIpProtocol.START_DATA -> {
                    val cursor = LittleEndianCursor(packet.body)
                    check(cursor.u32().toInt() == currentTransactionId)
                }

                PtpIpProtocol.DATA,
                PtpIpProtocol.END_DATA,
                -> data += PtpIpProtocol.dataPayload(
                    packet.body,
                    currentTransactionId,
                )

                PtpIpProtocol.OPERATION_RESPONSE -> {
                    val response =
                        PtpIpProtocol.parseOperationResponse(packet.body)
                    check(response.transactionId == currentTransactionId)

                    return TransactionResult(
                        response = response,
                        data = concatenate(data),
                        params = response.params,
                    )
                }

                PtpIpProtocol.PROBE_REQUEST -> {
                    sendPacket(
                        socket,
                        PtpIpProtocol.PROBE_RESPONSE,
                        byteArrayOf(),
                    )
                    Log.i(TAG, "PTP/IP command ProbeRequest acknowledged")
                }

                PtpIpProtocol.PROBE_RESPONSE -> {
                    Log.i(TAG, "PTP/IP command ProbeResponse received")
                }

                else -> error(
                    "Unexpected PTP/IP packet type ${packet.type}.",
                )
            }
        }
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

    private fun concatenate(chunks: List<ByteArray>): ByteArray {
        val size = chunks.sumOf { it.size }
        val output = ByteArray(size)
        var offset = 0

        for (chunk in chunks) {
            chunk.copyInto(output, offset)
            offset += chunk.size
        }

        return output
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
        const val CONTENTS_TRANSFER_RESET_MS = 200L
        const val CONTENTS_TRANSFER_SETTLE_MS = 1_500L

        const val SOCKET_CONNECT_TIMEOUT_MS = 2_000
        const val SOCKET_READ_TIMEOUT_MS = 15_000
        const val PORT_WAIT_TIMEOUT_MS = 30_000L
        const val PORT_RETRY_DELAY_MS = 1_000L
    }
}
