package com.groox.ocr.ui.components

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Overlay box bubble/line di atas preview.
 * [imageAspect] = width/height gambar asli; canvas di-scale seragam (fit-width).
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
    Canvas(modifier = modifier.fillMaxWidth()) {
        val sx = size.width / imageWidth
        // Canvas height di caller sudah diatur proporsional; pakai sx untuk x & y.
        boxes.forEachIndexed { i, b ->
            val left = b.left * sx
            val top = b.top * sx
            val w = b.width() * sx
            val h = b.height() * sx
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
