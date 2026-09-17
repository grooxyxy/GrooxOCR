package com.groox.ocr.engine

import android.graphics.Bitmap
import com.groox.ocr.data.OcrModels
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * Detection preprocess — mirrors Paddle `DetResizeForTest + NormalizeImage + ToCHW`:
 * - scale so long side == [detLongSide] (default 1280), keep aspect
 * - normalize RGB with ImageNet mean/std, scale 1/255
 * - pad H/W to multiple of 32 (DBNet stride requirement)
 * - output NCHW float32 with BGR→RGB handled from ARGB bitmap
 */
object DetPreprocess {

    data class DetInput(
        val buffer: FloatBuffer,
        val nchw: LongArray,
        /** scale = resized/padded mapping: orig → model input (before pad). */
        val scale: Float,
        val resizedW: Int,
        val resizedH: Int,
        val padW: Int,
        val padH: Int,
    )

    fun prepare(src: Bitmap, detLongSide: Int = 1280): DetInput {
        val sw = src.width
        val sh = src.height
        require(sw > 0 && sh > 0)
        val longSide = maxOf(sw, sh).toFloat()
        val scale = detLongSide / longSide
        var rw = (sw * scale).toInt().coerceAtLeast(32)
        var rh = (sh * scale).toInt().coerceAtLeast(32)
        // Anggaran piksel: input raksasa (mis. tile 720×1600 di long-side 4000
        // = 1800×4000) bisa OOM di HP. Batasi luas area, jaga aspek.
        // 4 juta px ≈ 1340×2980 untuk tile manhwa — masih tajam.
        val maxPix = 4_000_000L
        val area = rw.toLong() * rh
        if (area > maxPix) {
            val s = kotlin.math.sqrt(maxPix.toDouble() / area)
            rw = (rw * s).toInt().coerceAtLeast(32)
            rh = (rh * s).toInt().coerceAtLeast(32)
        }
        val padW = ceil(rw / 32.0).toInt() * 32
        val padH = ceil(rh / 32.0).toInt() * 32

        // Bilinear-ish resize via Bitmap (fast, no extra dep).
        val resized = Bitmap.createScaledBitmap(src, rw, rh, true)
        val pixels = IntArray(rw * rh)
        resized.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        if (resized !== src) resized.recycle()

        val buf = ByteBuffer
            .allocateDirect(1 * 3 * padH * padW * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Fill channel-first; pad area stays 0 (matches Paddle zero-pad).
        for (c in 0..2) {
            val mean: Float
            val std: Float
            when (c) {
                0 -> { mean = OcrModels.DET_MEAN_R; std = OcrModels.DET_STD_R }
                1 -> { mean = OcrModels.DET_MEAN_G; std = OcrModels.DET_STD_G }
                else -> { mean = OcrModels.DET_MEAN_B; std = OcrModels.DET_STD_B }
            }
            for (y in 0 until padH) {
                for (x in 0 until padW) {
                    val v: Float = if (y < rh && x < rw) {
                        val px = pixels[y * rw + x]
                        val ch = when (c) {
                            0 -> (px shr 16) and 0xFF // R
                            1 -> (px shr 8) and 0xFF // G
                            else -> px and 0xFF // B
                        }
                        ((ch / 255.0f) - mean) / std
                    } else 0f
                    buf.put(v)
                }
            }
        }
        buf.rewind()
        val actualScale = rw.toFloat() / sw // x and y share aspect (uniform scale)
        return DetInput(buf, longArrayOf(1, 3, padH.toLong(), padW.toLong()), actualScale, rw, rh, padW, padH)
    }
}
