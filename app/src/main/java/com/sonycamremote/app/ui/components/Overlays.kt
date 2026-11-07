package com.sonycamremote.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sonycamremote.app.data.prefs.GridMode
import com.sonycamremote.app.ui.theme.Cinema

@Composable
fun FramingOverlay(grid: GridMode, safeArea: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.35f)
        val sw = 1.dp.toPx()
        if (grid == GridMode.THIRDS || grid == GridMode.THIRDS_CROSSHAIR) {
            for (i in 1..2) {
                val x = size.width * i / 3f
                val y = size.height * i / 3f
                drawLine(line, Offset(x, 0f), Offset(x, size.height), sw)
                drawLine(line, Offset(0f, y), Offset(size.width, y), sw)
            }
        }
        if (grid == GridMode.CROSSHAIR || grid == GridMode.THIRDS_CROSSHAIR) {
            val c = center
            val l = 14.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x - l, c.y), Offset(c.x + l, c.y), sw * 1.5f)
            drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x, c.y - l), Offset(c.x, c.y + l), sw * 1.5f)
        }
        if (safeArea) {
            // 90% action-safe and 80% title-safe guides.
            listOf(0.9f to 0.5f, 0.8f to 0.3f).forEach { (f, a) ->
                val w = size.width * f
                val h = size.height * f
                drawRect(
                    Cinema.Accent.copy(alpha = a), Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h),
                    style = Stroke(sw),
                )
            }
        }
    }
}

/** Touch-AF marker drawn where the user tapped; colour reflects camera-reported focus state. */
@Composable
fun FocusMarker(x: Float, y: Float, focused: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val s = 56.dp.toPx()
        val c = Offset(size.width * x, size.height * y)
        drawRect(
            if (focused) Cinema.Success else Cinema.TextPrimary,
            Offset(c.x - s / 2, c.y - s / 2), Size(s, s), style = Stroke(2.dp.toPx()),
        )
    }
}

/** Luma histogram computed from the actual Live View frame. */
@Composable
fun Histogram(bins: IntArray, clipping: Float?, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 140.dp, height = 64.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.55f))
            val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            val bw = size.width / bins.size
            val path = Path().apply {
                moveTo(0f, size.height)
                bins.forEachIndexed { i, v ->
                    lineTo(i * bw + bw / 2, size.height - (v / max) * size.height * 0.95f)
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.75f))
            if (clipping != null && clipping > 0.02f) {
                drawRect(Cinema.Error, Offset(size.width - 6.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
            }
        }
    }
}
