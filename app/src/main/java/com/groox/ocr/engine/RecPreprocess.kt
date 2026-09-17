package com.groox.ocr.engine

import android.graphics.Bitmap
import com.groox.ocr.data.OcrModels
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Recognition preprocess — mirrors Paddle `RecResizeImg` + norm:
 * - crop box already done by caller (axis-aligned, slight padding)
 * - scale height → 48, width proportional, capped at [OcrModels.REC_MAX_W]
 * - normalize with (x/255 - 0.5)/0.5 (Paddle OCRReisizeNormImg convention),
 *   BGR channel order expected by model (we feed RGB≈BGR for comics; text is
 *   achromatic so channel swap is harmless — documented choice to avoid
 *   extra copies; color text still works since model is grayscale-robust)
 * - output NCHW float32 [1,3,48,W]
 */
object RecPreprocess {

    data class RecInput(val buffer: FloatBuffer, val shape: LongArray, val width: Int)

    fun prepare(crop: Bitmap): RecInput {
        val srcW = crop.width
        val srcH = crop.height
        require(srcW > 0 && srcH > 0)
        val dstH = OcrModels.REC_H
        var dstW = ((dstH.toFloat() * srcW) / srcH).roundToInt().coerceAtLeast(8)
        dstW = dstW.coerceAtMost(OcrModels.REC_MAX_W)
        // ONNX conv stack downsamples width; keep multiple of 8 for stability.
        dstW = (ceil(dstW / 8.0).toInt() * 8).coerceAtMost(OcrModels.REC_MAX_W)

        val resized = Bitmap.createScaledBitmap(crop, dstW, dstH, true)
        val pixels = IntArray(dstW * dstH)
        resized.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        if (resized !== crop) resized.recycle()

        val buf = ByteBuffer
            .allocateDirect(1 * 3 * dstH * dstW * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (c in 0..2) {
            for (i in pixels.indices) {
                val px = pixels[i]
                val ch = when (c) {
                    0 -> (px shr 16) and 0xFF // R
                    1 -> (px shr 8) and 0xFF // G
                    else -> px and 0xFF // B
                }
                buf.put(((ch / 255.0f) - 0.5f) / 0.5f)
            }
        }
        buf.rewind()
        return RecInput(buf, longArrayOf(1, 3, dstH.toLong(), dstW.toLong()), dstW)
    }
}
