package com.sonycamremote.app.data.camera.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.model.CameraConnectionException
import com.sonycamremote.engine.model.CameraProtocolException
import com.sonycamremote.engine.ptp.PtpRequest
import com.sonycamremote.engine.ptp.PtpResult
import com.sonycamremote.engine.ptp.PtpTransport
import com.sonycamremote.engine.ptp.PtpWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP over USB (Still Image class, bulk-only container transport) using Android's USB host API.
 * Requires the camera's USB Connection setting = "PC Remote" and a granted USB permission.
 */
class UsbPtpTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val log: DiagnosticsLog,
) : PtpTransport {

    override val description = "USB ${device.productName ?: device.deviceName}"

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private val readBuffer = ByteArray(READ_CHUNK)

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (!usbManager.hasPermission(device)) throw CameraConnectionException("USB permission not granted")
        val (i, inEp, outEp) = findPtpInterface(device)
            ?: throw CameraConnectionException("Device exposes no PTP interface. Set USB Connection to 'PC Remote'.")
        val c = usbManager.openDevice(device) ?: throw CameraConnectionException("Could not open USB device")
        if (!c.claimInterface(i, true)) {
            c.close()
            throw CameraConnectionException("Could not claim USB interface (another app may be using the camera)")
        }
        connection = c
        iface = i
        epIn = inEp
        epOut = outEp
        log.i("USB", "Opened ${device.deviceName} vid=0x%04X pid=0x%04X".format(device.vendorId, device.productId))
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        val c = connection
        iface?.let { runCatching { c?.releaseInterface(it) } }
        runCatching { c?.close() }
        connection = null
        iface = null
        Unit
    }

    override suspend fun transact(request: PtpRequest, timeoutMs: Int): PtpResult = withContext(Dispatchers.IO) {
        val c = connection ?: throw CameraConnectionException("USB not open")
        val out = epOut!!
        val inp = epIn!!

        // Command container
        val cmd = PtpWriter()
            .u32(12 + 4 * request.params.size).u16(TYPE_COMMAND).u16(request.opCode).u32(request.transactionId)
        request.params.forEach { cmd.u32(it) }
        bulkOut(c, out, cmd.toByteArray(), timeoutMs)

        // Optional data phase host → device
        request.dataOut?.let { data ->
            val header = PtpWriter().u32(12 + data.size).u16(TYPE_DATA).u16(request.opCode).u32(request.transactionId)
            bulkOut(c, out, header.bytes(data).toByteArray(), timeoutMs)
        }

        var data: ByteArray? = null
        while (true) {
            val (type, code, payload) = readContainer(c, inp, timeoutMs)
            when (type) {
                TYPE_DATA -> data = payload
                TYPE_RESPONSE -> {
                    val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    val params = IntArray(payload.size / 4) { bb.int }
                    return@withContext PtpResult(code, params, data)
                }
                else -> log.w("USB", "Unexpected container type $type")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun bulkOut(c: UsbDeviceConnection, ep: UsbEndpoint, bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(WRITE_CHUNK, bytes.size - offset)
            val n = c.bulkTransfer(ep, bytes, offset, len, timeoutMs)
            if (n < 0) throw CameraConnectionException("USB write failed (camera disconnected?)")
            offset += n
        }
        // A transfer that is an exact multiple of the packet size must be terminated by a ZLP.
        if (bytes.isNotEmpty() && bytes.size % ep.maxPacketSize == 0) {
            c.bulkTransfer(ep, ByteArray(0), 0, timeoutMs)
        }
    }

    private data class Container(val type: Int, val code: Int, val payload: ByteArray)

    private fun readContainer(c: UsbDeviceConnection, ep: UsbEndpoint, timeoutMs: Int): Container {
        var n = c.bulkTransfer(ep, readBuffer, readBuffer.size, timeoutMs)
        // Some bodies send a stray zero-length packet; read again.
        if (n == 0) n = c.bulkTransfer(ep, readBuffer, readBuffer.size, timeoutMs)
        if (n < 12) throw CameraConnectionException("USB read failed or timed out ($n)")
        val bb = ByteBuffer.wrap(readBuffer, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int.toLong() and 0xFFFFFFFFL
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        bb.int // transaction id
        if (length < 12 || length > MAX_CONTAINER) throw CameraProtocolException("Bad USB container length $length")
        val total = length.toInt()
        val acc = ByteArrayOutputStream(total)
        acc.write(readBuffer, 12, n - 12)
        var received = n
        while (received < total) {
            val r = c.bulkTransfer(ep, readBuffer, readBuffer.size, timeoutMs)
            if (r < 0) throw CameraConnectionException("USB read failed mid-container")
            if (r == 0) continue
            acc.write(readBuffer, 0, r)
            received += r
        }
        return Container(type, code, acc.toByteArray())
    }

    companion object {
        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
        const val TYPE_RESPONSE = 3
        const val TYPE_EVENT = 4
        private const val READ_CHUNK = 512 * 1024
        private const val WRITE_CHUNK = 16 * 1024
        private const val MAX_CONTAINER = 64L * 1024 * 1024
        const val SONY_VENDOR_ID = 0x054C

        /** Finds the Still Image (PTP) interface and its bulk endpoints. */
        fun findPtpInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_STILL_IMAGE) continue
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                if (inEp != null && outEp != null) return Triple(intf, inEp, outEp)
            }
            return null
        }
    }
}
