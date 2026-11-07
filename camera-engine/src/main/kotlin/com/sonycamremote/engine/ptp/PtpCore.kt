package com.sonycamremote.engine.ptp

import com.sonycamremote.engine.model.CameraProtocolException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Standard ISO 15740 (PTP) codes used by this app. */
object Ptp {
    // Operations
    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009

    // Responses
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    const val RC_ACCESS_DENIED = 0x200F

    // Data types
    const val DT_INT8 = 0x0001
    const val DT_UINT8 = 0x0002
    const val DT_INT16 = 0x0003
    const val DT_UINT16 = 0x0004
    const val DT_INT32 = 0x0005
    const val DT_UINT32 = 0x0006
    const val DT_INT64 = 0x0007
    const val DT_UINT64 = 0x0008
    const val DT_ARRAY_FLAG = 0x4000
    const val DT_STR = 0xFFFF

    // Property form flags
    const val FORM_NONE = 0x00
    const val FORM_RANGE = 0x01
    const val FORM_ENUM = 0x02

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "GeneralError"
        RC_SESSION_NOT_OPEN -> "SessionNotOpen"
        RC_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RC_ACCESS_DENIED -> "AccessDenied"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        else -> "0x%04X".format(code)
    }
}

class PtpException(val op: Int, val responseCode: Int) :
    CameraProtocolException("PTP op 0x%04X failed: %s".format(op, Ptp.responseName(responseCode)), responseCode)

data class PtpRequest(
    val opCode: Int,
    val transactionId: Int,
    val params: IntArray = IntArray(0),
    /** Data sent host → camera in the data phase, or null. */
    val dataOut: ByteArray? = null,
)

class PtpResult(val responseCode: Int, val params: IntArray, val data: ByteArray?)

/**
 * A physical PTP link (USB bulk endpoints, or a PTP-IP command/event socket pair). Implementations
 * are not required to be thread-safe; [PtpSession] serialises all access.
 */
interface PtpTransport {
    val description: String
    suspend fun open()
    suspend fun close()
    suspend fun transact(request: PtpRequest, timeoutMs: Int): PtpResult
}

/** Owns transaction ids and the session; every PTP call in the app goes through here. */
class PtpSession(private val transport: PtpTransport) {
    private val mutex = Mutex()
    private var nextTransaction = 1
    var isOpen = false
        private set

    suspend fun openSession(sessionId: Int = 1) = mutex.withLock {
        val r = transport.transact(PtpRequest(Ptp.OC_OPEN_SESSION, 0, intArrayOf(sessionId)), 5000)
        if (r.responseCode != Ptp.RC_OK && r.responseCode != Ptp.RC_SESSION_ALREADY_OPEN) {
            throw PtpException(Ptp.OC_OPEN_SESSION, r.responseCode)
        }
        nextTransaction = 1
        isOpen = true
    }

    suspend fun closeSession() = mutex.withLock {
        if (!isOpen) return@withLock
        runCatching { transport.transact(PtpRequest(Ptp.OC_CLOSE_SESSION, nextTransaction++), 2000) }
        isOpen = false
    }

    /** Runs one operation and throws [PtpException] unless the response is OK. */
    suspend fun op(
        code: Int,
        vararg params: Int,
        dataOut: ByteArray? = null,
        timeoutMs: Int = 5000,
    ): PtpResult = mutex.withLock {
        val r = transport.transact(PtpRequest(code, nextTransaction++, params, dataOut), timeoutMs)
        if (r.responseCode != Ptp.RC_OK) throw PtpException(code, r.responseCode)
        r
    }
}

// ---------------------------------------------------------------------------------- wire helpers

/** Little-endian reader over a PTP dataset. */
class PtpReader(private val buf: ByteBuffer) {
    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

    val remaining: Int get() = buf.remaining()
    val position: Int get() = buf.position()

    fun u8() = buf.get().toInt() and 0xFF
    fun i8() = buf.get().toInt()
    fun u16() = buf.short.toInt() and 0xFFFF
    fun i16() = buf.short.toInt()
    fun u32() = buf.int.toLong() and 0xFFFFFFFFL
    fun i32() = buf.int
    fun u64() = buf.long
    fun skip(n: Int) { buf.position(buf.position() + n) }

    /** PTP string: u8 character count (including NUL), then UTF-16LE code units. */
    fun string(): String {
        val n = u8()
        if (n == 0) return ""
        val chars = CharArray(n) { buf.short.toInt().toChar() }
        return String(chars).trimEnd('\u0000')
    }

    fun u16Array(): List<Int> {
        val n = u32().toInt()
        require(n in 0..65535) { "array length $n" }
        return List(n) { u16() }
    }

    /** Reads one value of PTP [dataType]. */
    fun value(dataType: Int): PtpValue = when {
        dataType == Ptp.DT_STR -> PtpValue.Str(string())
        dataType and Ptp.DT_ARRAY_FLAG != 0 -> {
            val n = u32().toInt()
            require(n in 0..100_000) { "array length $n" }
            val element = dataType and Ptp.DT_ARRAY_FLAG.inv()
            PtpValue.Arr(List(n) { (scalar(element) as PtpValue.Num).value })
        }
        else -> scalar(dataType)
    }

    private fun scalar(t: Int): PtpValue = PtpValue.Num(
        when (t) {
            Ptp.DT_INT8 -> i8().toLong()
            Ptp.DT_UINT8 -> u8().toLong()
            Ptp.DT_INT16 -> i16().toLong()
            Ptp.DT_UINT16 -> u16().toLong()
            Ptp.DT_INT32 -> i32().toLong()
            Ptp.DT_UINT32 -> u32()
            Ptp.DT_INT64, Ptp.DT_UINT64 -> u64()
            0x0009, 0x000A -> { val lo = u64(); skip(8); lo } // 128-bit: keep low word
            else -> throw IllegalArgumentException("Unknown PTP data type 0x%04X".format(t))
        },
    )
}

class PtpWriter {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int): PtpWriter = apply { out.write(v and 0xFF) }
    fun u16(v: Int): PtpWriter = apply { u8(v); u8(v shr 8) }
    fun u32(v: Long): PtpWriter = apply { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
    fun u32(v: Int): PtpWriter = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long): PtpWriter = apply { for (i in 0 until 8) u8((v shr (8 * i)).toInt()) }
    fun bytes(b: ByteArray): PtpWriter = apply { out.write(b) }
    fun string(s: String): PtpWriter = apply {
        if (s.isEmpty()) { u8(0); return@apply }
        u8(s.length + 1)
        s.forEach { u16(it.code) }
        u16(0)
    }

    fun value(dataType: Int, v: Long): PtpWriter = apply {
        when (dataType) {
            Ptp.DT_INT8, Ptp.DT_UINT8 -> u8(v.toInt())
            Ptp.DT_INT16, Ptp.DT_UINT16 -> u16(v.toInt())
            Ptp.DT_INT32, Ptp.DT_UINT32 -> u32(v)
            Ptp.DT_INT64, Ptp.DT_UINT64 -> u64(v)
            else -> throw IllegalArgumentException("Cannot encode PTP type 0x%04X".format(dataType))
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

sealed class PtpValue {
    data class Num(val value: Long) : PtpValue()
    data class Str(val value: String) : PtpValue()
    data class Arr(val values: List<Long>) : PtpValue()

    fun asLong(): Long? = (this as? Num)?.value
}

data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val operations: Set<Int>,
    val events: Set<Int>,
    val properties: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    companion object {
        fun parse(data: ByteArray): PtpDeviceInfo {
            val r = PtpReader(data)
            val std = r.u16()
            val vendorId = r.u32()
            val vendorVer = r.u16()
            val vendorDesc = r.string()
            r.u16() // functional mode
            val ops = r.u16Array().toSet()
            val events = r.u16Array().toSet()
            val props = r.u16Array().toSet()
            r.u16Array() // capture formats
            r.u16Array() // image formats
            return PtpDeviceInfo(
                std, vendorId, vendorVer, vendorDesc, ops, events, props,
                manufacturer = r.string(), model = r.string(), deviceVersion = r.string(), serialNumber = r.string(),
            )
        }
    }
}
