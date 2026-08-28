package com.chambersxdu.alphaphoto

import android.content.Context
import android.net.Network
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class PtpIpProbe(context: Context) {
    private val appContext = context.applicationContext

    fun initialize(
        cameraNetwork: CameraNetwork,
        onStatus: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                val host = cameraNetwork.gateway.hostAddress
                checkNotNull(host)

                post(onStatus, "Waiting for Sony PTP/IP…")
                waitForPort(cameraNetwork.network, host)

                cameraNetwork.network.socketFactory.createSocket().use { commandSocket ->
                    connect(commandSocket, host)

                    val commandBody = buildInitCommandBody()
                    sendPacket(commandSocket, INIT_COMMAND_REQUEST, commandBody)

                    val commandAck = receivePacket(commandSocket)
                    if (commandAck.type == INIT_FAIL) {
                        val reason = if (commandAck.body.size >= 4) {
                            littleEndianInt(commandAck.body, 0)
                        } else {
                            -1
                        }
                        error("Sony rejected PTP/IP initialization. reason=$reason")
                    }
                    check(commandAck.type == INIT_COMMAND_ACK)
                    check(commandAck.body.size >= 4)

                    val connectionNumber = littleEndianInt(commandAck.body, 0)
                    Log.i(
                        TAG,
                        "PTP/IP command channel ready connectionNumber=$connectionNumber",
                    )

                    cameraNetwork.network.socketFactory.createSocket().use { eventSocket ->
                        connect(eventSocket, host)

                        sendPacket(
                            eventSocket,
                            INIT_EVENT_REQUEST,
                            littleEndianBytes(connectionNumber),
                        )

                        val eventAck = receivePacket(eventSocket)
                        check(eventAck.type == INIT_EVENT_ACK)

                        Log.i(
                            TAG,
                            "PTP/IP event channel ready host=$host port=$PTP_IP_PORT",
                        )
                        post(onStatus, "PTP/IP initialization succeeded.")
                        post(onSuccess, Unit)
                    }
                }
            } catch (error: Throwable) {
                val message = "PTP/IP initialization failed: ${error.message}"
                Log.e(TAG, message, error)
                post(onError, message)
            }
        }.start()
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
                        InetSocketAddress(host, PTP_IP_PORT),
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

        error("Sony PTP/IP port did not become reachable: ${lastError?.message}")
    }

    private fun connect(
        socket: Socket,
        host: String,
    ) {
        socket.connect(
            InetSocketAddress(host, PTP_IP_PORT),
            SOCKET_CONNECT_TIMEOUT_MS,
        )
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        socket.tcpNoDelay = true
    }

    private fun buildInitCommandBody(): ByteArray {
        val guid = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(UUID.randomUUID().mostSignificantBits)
            .putLong(UUID.randomUUID().leastSignificantBits)
            .array()

        val name = "Alpha Photo".toByteArray(Charsets.UTF_16LE)
        val version = littleEndianBytes(PTP_IP_VERSION)

        return guid + name + byteArrayOf(0x00, 0x00) + version
    }

    private fun sendPacket(
        socket: Socket,
        type: Int,
        body: ByteArray,
    ) {
        val packet = ByteBuffer.allocate(body.size + 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(body.size + 8)
            .putInt(type)
            .put(body)
            .array()

        socket.getOutputStream().write(packet)
        socket.getOutputStream().flush()
    }

    private fun receivePacket(socket: Socket): Packet {
        val input = socket.getInputStream()
        val header = input.readExactly(8)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val length = buffer.int
        val type = buffer.int

        check(length >= 8)
        return Packet(
            type = type,
            body = input.readExactly(length - 8),
        )
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val output = ByteArray(count)
        var offset = 0

        while (offset < count) {
            val read = read(output, offset, count - offset)
            check(read >= 0)
            offset += read
        }

        return output
    }

    private fun littleEndianBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun littleEndianInt(
        value: ByteArray,
        offset: Int,
    ): Int =
        ByteBuffer.wrap(value, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun <T> post(
        callback: (T) -> Unit,
        value: T,
    ) {
        appContext.mainExecutor.execute {
            callback(value)
        }
    }

    private data class Packet(
        val type: Int,
        val body: ByteArray,
    )

    private companion object {
        const val TAG = "AlphaPhoto"

        const val PTP_IP_PORT = 15740
        const val PTP_IP_VERSION = 0x00010000

        const val INIT_COMMAND_REQUEST = 1
        const val INIT_COMMAND_ACK = 2
        const val INIT_EVENT_REQUEST = 3
        const val INIT_EVENT_ACK = 4
        const val INIT_FAIL = 5

        const val SOCKET_CONNECT_TIMEOUT_MS = 2_000
        const val SOCKET_READ_TIMEOUT_MS = 15_000
        const val PORT_WAIT_TIMEOUT_MS = 30_000L
        const val PORT_RETRY_DELAY_MS = 1_000L
    }
}
