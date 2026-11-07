package com.sonycamremote.engine.legacy

import com.sonycamremote.engine.model.CameraProtocolException
import com.sonycamremote.engine.model.LiveViewFrame
import java.io.EOFException
import java.io.InputStream

/**
 * Parser for the legacy Camera Remote API Live View stream (HTTP body returned by the startLiveview URL).
 *
 * Packet layout (Sony Camera Remote API reference, "Liveview data format"):
 *   Common header  (8 bytes):  0xFF | payloadType | sequence(u16 BE) | timestamp(u32 BE)
 *   Payload header (128 bytes): startCode 0x24 0x35 0x68 0x79 | dataSize(u24 BE) | paddingSize(u8) | ...
 *   Payload: dataSize bytes, then paddingSize bytes of padding.
 * payloadType 0x01 = JPEG image, 0x02 = frame information (focus frames etc.).
 */
class LiveViewStreamParser(private val input: InputStream, private val maxFrameBytes: Int = 4 * 1024 * 1024) {

    private val common = ByteArray(COMMON_HEADER_SIZE)
    private val payloadHeader = ByteArray(PAYLOAD_HEADER_SIZE)

    /** Returns the next JPEG frame, skipping non-image payloads; null on clean end of stream. */
    fun nextFrame(): LiveViewFrame? {
        while (true) {
            if (!readCommonHeader()) return null
            val payloadType = common[1].toInt() and 0xFF
            val sequence = ((common[2].toInt() and 0xFF) shl 8) or (common[3].toInt() and 0xFF)

            readFully(payloadHeader, PAYLOAD_HEADER_SIZE)
            if (!startCodeMatches()) {
                throw CameraProtocolException("Live View payload header start code mismatch")
            }
            val dataSize = ((payloadHeader[4].toInt() and 0xFF) shl 16) or
                ((payloadHeader[5].toInt() and 0xFF) shl 8) or
                (payloadHeader[6].toInt() and 0xFF)
            val padding = payloadHeader[7].toInt() and 0xFF
            if (dataSize < 0 || dataSize > maxFrameBytes) {
                throw CameraProtocolException("Live View payload size out of range: $dataSize")
            }

            if (payloadType == PAYLOAD_JPEG) {
                val jpeg = ByteArray(dataSize)
                readFully(jpeg, dataSize)
                skipFully(padding.toLong())
                return LiveViewFrame(jpeg, sequence, System.nanoTime())
            } else {
                skipFully(dataSize.toLong() + padding)
            }
        }
    }

    /** Reads up to the next 0xFF start byte, tolerating garbage between packets. False on EOF. */
    private fun readCommonHeader(): Boolean {
        var b: Int
        do {
            b = input.read()
            if (b < 0) return false
        } while (b != 0xFF)
        common[0] = 0xFF.toByte()
        readFully(common, COMMON_HEADER_SIZE - 1, offset = 1)
        return true
    }

    private fun startCodeMatches() =
        payloadHeader[0] == 0x24.toByte() && payloadHeader[1] == 0x35.toByte() &&
            payloadHeader[2] == 0x68.toByte() && payloadHeader[3] == 0x79.toByte()

    private fun readFully(buf: ByteArray, len: Int, offset: Int = 0) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, offset + read, len - read)
            if (n < 0) throw EOFException("Live View stream ended mid-packet")
            read += n
        }
    }

    private fun skipFully(len: Long) {
        var remaining = len
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("Live View stream ended while skipping")
            remaining -= n
        }
    }

    companion object {
        const val COMMON_HEADER_SIZE = 8
        const val PAYLOAD_HEADER_SIZE = 128
        const val PAYLOAD_JPEG = 0x01
    }
}
