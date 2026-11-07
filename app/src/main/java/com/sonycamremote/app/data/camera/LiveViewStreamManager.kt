package com.sonycamremote.app.data.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import com.sonycamremote.engine.protocol.CameraProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A decoded Live View frame plus measured stream statistics. */
class DecodedFrame(
    val bitmap: Bitmap,
    val fps: Float,
    /** Time from frame arrival to decode finished — the part of latency this phone adds. */
    val decodeMs: Long,
    /** Luma histogram (64 bins), computed every few frames; null when not computed for this frame. */
    val histogram: IntArray?,
    /** Fraction of sampled pixels at or near pure white (exposure warning). */
    val highlightClipping: Float?,
)

/**
 * Turns a protocol's raw JPEG stream into decoded bitmaps. Drops stale frames (conflation) so the
 * preview never lags behind the camera, and restarts the stream after transient errors.
 * Every pixel shown comes from the camera — nothing is synthesised.
 */
class LiveViewStreamManager(private val log: DiagnosticsLog) {

    fun frames(protocol: CameraProtocol, large: Boolean, histogram: Boolean): Flow<DecodedFrame> = channelFlow {
        var failures = 0
        while (true) {
            try {
                protocol.liveView(large)
                    .buffer(1, BufferOverflow.DROP_OLDEST)
                    .map { decode(it.jpeg, it.receivedAtNanos, histogram) }
                    .flowOn(Dispatchers.Default)
                    .collect { decoded ->
                        failures = 0
                        if (decoded != null) send(decoded)
                    }
                return@channelFlow // stream ended cleanly
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                log.w("LiveView", "Stream error ($failures): ${e.message}")
                if (failures >= 5) throw e
                delay(500L * failures)
            }
        }
    }.buffer(1, BufferOverflow.DROP_OLDEST)

    private var lastFrameNanos = 0L
    private var fpsEma = 0f
    private var frameCount = 0
    private var lastHistogram: IntArray? = null
    private var lastClipping: Float? = null

    private fun decode(jpeg: ByteArray, receivedAt: Long, wantHistogram: Boolean): DecodedFrame? {
        val display = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null

        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            val inst = 1e9f / (now - lastFrameNanos).coerceAtLeast(1)
            fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.85f + inst * 0.15f
        }
        lastFrameNanos = now
        frameCount++
        if (wantHistogram && frameCount % 4 == 0) computeHistogram(display)
        return DecodedFrame(
            display, fpsEma, (System.nanoTime() - receivedAt) / 1_000_000,
            if (wantHistogram) lastHistogram else null, if (wantHistogram) lastClipping else null,
        )
    }

    private fun computeHistogram(bmp: Bitmap) {
        val w = 96
        val h = (96f * bmp.height / bmp.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, false)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val bins = IntArray(64)
        var clipped = 0
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 299 + g * 587 + b * 114) / 1000
            bins[y shr 2]++
            if (y >= 250) clipped++
        }
        lastHistogram = bins
        lastClipping = clipped.toFloat() / px.size
    }
}
