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

    fun listRecentFiles(
        slot: Int,
        onStatus: (String) -> Unit,
        onSuccess: (List<SonyContentFile>) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                require(slot > 0)
                post(onStatus, "Listing recent photos from slot $slot…")

                val dates = transactChecked(
                    opcode = SonyMediaProtocol.OP_SDIO_GET_CAPTURED_DATE_LIST,
                    params = listOf(slot),
                    name = "SDIO_GetCapturedDateList",
                ).data

                val capturedDates = SonyMediaProtocol.parseCapturedDates(dates)
                if (capturedDates.isEmpty()) {
                    post(onSuccess, emptyList())
                    return@Thread
                }

                val latestDate = capturedDates.max()
                val contents = transactChecked(
                    opcode = SonyMediaProtocol.OP_SDIO_GET_CONTENTS_INFO_LIST,
                    params = SonyMediaProtocol.contentsInfoParams(
                        captureDate = latestDate,
                        count = RECENT_CONTENT_LIMIT,
                        slot = slot,
                    ),
                    name = "SDIO_GetContentsInfoList",
                ).data

                val files = SonyMediaProtocol.parseContentsInfoList(contents).files
                Log.i(
                    TAG,
                    "Sony recent contents slot=$slot dates=${capturedDates.size} files=${files.size}",
                )
                files.forEach { file ->
                    Log.i(
                        TAG,
                        "Sony file name=${file.name} format=0x${file.formatCode.toString(16)} " +
                            "size=${file.size} dimensions=${file.width}x${file.height} " +
                            "uniqueId=0x${file.uniqueId.toULong().toString(16)}",
                    )
                }

                post(onSuccess, files)
            } catch (error: Throwable) {
                val message = "Sony contents listing failed: ${error.message}"
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

        transactChecked(
            opcode = SonyMediaProtocol.OP_SDIO_SET_CONTENTS_TRANSFER_MODE,
            params = listOf(
                SonyMediaProtocol.CONTENTS_SELECT_REMOTE_DEVICE,
                SonyMediaProtocol.CONTENTS_TRANSFER_ON,
                SonyMediaProtocol.CONTENTS_INFO_NONE,
            ),
            name = "SDIO_SetContentsTransferMode",
        )
        Log.i(TAG, "Sony remote-device content transfer enabled")
        Log.i(TAG, "Sony PTP transfer session ready")
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
            "$name failed with response 0x${result.response.code.toString(16)}."
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
        const val RECENT_CONTENT_LIMIT = 60

        const val SOCKET_CONNECT_TIMEOUT_MS = 2_000
        const val SOCKET_READ_TIMEOUT_MS = 15_000
        const val PORT_WAIT_TIMEOUT_MS = 30_000L
        const val PORT_RETRY_DELAY_MS = 1_000L
    }
}
