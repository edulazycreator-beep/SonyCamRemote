package com.sonycamremote.engine.ptp

import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.CameraProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * PTP over TCP/IP (PTP-IP, CIPA DC-005). Command channel + event channel on port 15740.
 *
 * Sony bodies that support Wi-Fi/LAN "PC Remote" use this transport. Cameras with
 * "Access Authentication" enabled require an extra authentication step that Sony documents only in
 * the Camera Remote Command package; until that is implemented, authentication must be disabled on
 * the camera (see docs/COMPATIBILITY.md).
 */
class PtpIpTransport(
    private val host: String,
    private val port: Int = 15740,
    private val log: DiagnosticsLog,
    private val clientName: String = "SonyCam Remote",
    private val guid: UUID = UUID.nameUUIDFromBytes("SonyCamRemote-host".toByteArray()),
) : PtpTransport {

    override val description = "PTP-IP $host:$port"

    private var command: Socket? = null
    private var event: Socket? = null
    private var cmdIn: DataInputStream? = null
    private var cmdOut: OutputStream? = null
    private var eventScope: CoroutineScope? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) { openBlocking() }
    }

    private suspend fun openBlocking() {
        try {
            val c = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), 5000)
                soTimeout = 10_000
            }
            command = c
            cmdIn = DataInputStream(c.getInputStream().buffered())
            cmdOut = c.getOutputStream()

            // Init Command Request
            val init = PtpWriter().bytes(guidBytes()).string(clientName).u32(0x00010000).toByteArray()
            writePacket(TYPE_INIT_COMMAND_REQUEST, init)
            val (type, payload) = readPacket()
            if (type == TYPE_INIT_FAIL) {
                val reason = if (payload.size >= 4) ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int else -1
                throw CameraConnectionException("Camera rejected PTP-IP connection (reason $reason). Is the camera in PC Remote mode with access authentication off?")
            }
            if (type != TYPE_INIT_COMMAND_ACK) throw CameraProtocolException("Unexpected PTP-IP packet $type during init")
            val connectionNumber = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
            val name = runCatching { PtpReader(payload.copyOfRange(20, payload.size)).let { it.string() } }.getOrNull()
            log.i("PTP-IP", "Command channel open, connection #$connectionNumber, responder '$name'")

            // Event channel
            val e = Socket().apply {
                connect(InetSocketAddress(host, port), 5000)
                soTimeout = 0
            }
            event = e
            val evOut = e.getOutputStream()
            val evIn = DataInputStream(e.getInputStream().buffered())
            evOut.write(packet(TYPE_INIT_EVENT_REQUEST, PtpWriter().u32(connectionNumber).toByteArray()))
            evOut.flush()
            val evType = Integer.reverseBytes(evIn.readInt()).let { len ->
                val t = Integer.reverseBytes(evIn.readInt())
                evIn.skipBytes(len - 8)
                t
            }
            if (evType != TYPE_INIT_EVENT_ACK) throw CameraProtocolException("Event channel init failed (type $evType)")

            // Drain events so the camera never blocks on a full socket buffer.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            eventScope = scope
            scope.launch {
                try {
                    while (isActive) {
                        val len = Integer.reverseBytes(evIn.readInt())
                        val t = Integer.reverseBytes(evIn.readInt())
                        val body = ByteArray(len - 8)
                        evIn.readFully(body)
                        if (t == TYPE_EVENT && body.size >= 2) {
                            val code = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
                            log.d("PTP-IP", "Event 0x%04X".format(code))
                        } else if (t == TYPE_PROBE_REQUEST) {
                            synchronized(e) { evOut.write(packet(TYPE_PROBE_RESPONSE, ByteArray(0))); evOut.flush() }
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed.
                }
            }
        } catch (ex: IOException) {
            close()
            throw CameraConnectionException("PTP-IP connect to $host:$port failed: ${ex.message}", ex)
        }
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        eventScope?.cancel()
        eventScope = null
        runCatching { command?.close() }
        runCatching { event?.close() }
        command = null
        event = null
        cmdIn = null
        cmdOut = null
    }

    override suspend fun transact(request: PtpRequest, timeoutMs: Int): PtpResult = withContext(Dispatchers.IO) {
        val sock = command ?: throw CameraConnectionException("PTP-IP not connected")
        sock.soTimeout = timeoutMs
        try {
            val dataPhase = if (request.dataOut != null) 2 else 1
            val w = PtpWriter().u32(dataPhase).u16(request.opCode).u32(request.transactionId)
            request.params.forEach { w.u32(it) }
            writePacket(TYPE_OPERATION_REQUEST, w.toByteArray())

            request.dataOut?.let { data ->
                writePacket(TYPE_START_DATA, PtpWriter().u32(request.transactionId).u64(data.size.toLong()).toByteArray())
                writePacket(TYPE_END_DATA, PtpWriter().u32(request.transactionId).bytes(data).toByteArray())
            }

            var incoming: ByteArrayOutputStream? = null
            while (true) {
                val (type, payload) = readPacket()
                when (type) {
                    TYPE_START_DATA -> {
                        val total = ByteBuffer.wrap(payload, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
                        incoming = ByteArrayOutputStream(total.coerceIn(0, 32L * 1024 * 1024).toInt())
                    }
                    TYPE_DATA, TYPE_END_DATA -> {
                        (incoming ?: ByteArrayOutputStream().also { incoming = it }).write(payload, 4, payload.size - 4)
                    }
                    TYPE_OPERATION_RESPONSE -> {
                        val r = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        val code = r.short.toInt() and 0xFFFF
                        r.int // transaction id
                        val params = IntArray(r.remaining() / 4) { r.int }
                        return@withContext PtpResult(code, params, incoming?.toByteArray())
                    }
                    TYPE_PROBE_REQUEST -> writePacket(TYPE_PROBE_RESPONSE, ByteArray(0))
                    TYPE_CANCEL -> throw CameraProtocolException("Camera cancelled transaction")
                    else -> log.w("PTP-IP", "Ignoring packet type $type")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } catch (ex: IOException) {
            throw CameraConnectionException("PTP-IP I/O error: ${ex.message}", ex)
        }
    }

    private fun guidBytes(): ByteArray = ByteBuffer.allocate(16)
        .putLong(guid.mostSignificantBits).putLong(guid.leastSignificantBits).array()

    private fun packet(type: Int, payload: ByteArray): ByteArray =
        PtpWriter().u32(payload.size + 8).u32(type).bytes(payload).toByteArray()

    private fun writePacket(type: Int, payload: ByteArray) {
        val out = cmdOut ?: throw CameraConnectionException("PTP-IP not connected")
        out.write(packet(type, payload))
        out.flush()
    }

    private fun readPacket(): Pair<Int, ByteArray> {
        val input = cmdIn ?: throw CameraConnectionException("PTP-IP not connected")
        val len = Integer.reverseBytes(input.readInt())
        val type = Integer.reverseBytes(input.readInt())
        if (len < 8 || len > 64 * 1024 * 1024) throw CameraProtocolException("Bad PTP-IP packet length $len")
        val payload = ByteArray(len - 8)
        input.readFully(payload)
        return type to payload
    }

    companion object {
        const val TYPE_INIT_COMMAND_REQUEST = 1
        const val TYPE_INIT_COMMAND_ACK = 2
        const val TYPE_INIT_EVENT_REQUEST = 3
        const val TYPE_INIT_EVENT_ACK = 4
        const val TYPE_INIT_FAIL = 5
        const val TYPE_OPERATION_REQUEST = 6
        const val TYPE_OPERATION_RESPONSE = 7
        const val TYPE_EVENT = 8
        const val TYPE_START_DATA = 9
        const val TYPE_DATA = 10
        const val TYPE_CANCEL = 11
        const val TYPE_END_DATA = 12
        const val TYPE_PROBE_REQUEST = 13
        const val TYPE_PROBE_RESPONSE = 14
    }
}
