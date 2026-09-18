package com.groox.ocr.ui.components

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Overlay box bubble/line di atas preview.
 * Gambar ditampilkan dengan ContentScale.Fit di dalam Box → ada bilah
 * letterbox bila aspek tak pas. Overlay WAJIB memakai matematika Fit yang
 * sama, kalau tidak kotak bergeser dari sasaran (bug yang dilaporkan).
 */
@Composable
fun OcrOverlay(
    boxes: List<RectF>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    boxColor: Color = Color(0xFF1A73E8),
    showIndex: Boolean = false,
) {
    if (imageWidth <= 0 || imageHeight <= 0 || boxes.isEmpty()) return
    // fillMaxSize: Box pemanggil sudah berukuran aspek gambar; fillMaxWidth saja
    // membuat tinggi Canvas 0 sehingga box tidak terlihat.
    Canvas(modifier = modifier.fillMaxSize()) {
        // Matematika ContentScale.Fit: gambar diskala seragam + di tengah.
        val scale = minOf(size.width / imageWidth, size.height / imageHeight)
        val dx = (size.width - imageWidth * scale) / 2f
        val dy = (size.height - imageHeight * scale) / 2f
        boxes.forEachIndexed { i, b ->
            val left = dx + b.left * scale
            val top = dy + b.top * scale
            val w = b.width() * scale
            val h = b.height() * scale
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
