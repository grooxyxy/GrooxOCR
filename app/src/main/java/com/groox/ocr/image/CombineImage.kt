package com.groox.ocr.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.pdf.ZipKit
import java.io.File

/**
 * Gabung vertikal: banyak gambar ditumpuk jadi strip panjang.
 * Lebar disamakan (targetW), output dipecah per [maxOutH] px agar aman memori.
 * Hasil: file-file JPG + ZIP. Full offline.
 */
object CombineImage {

    data class Result(val images: List<File>, val zip: File, val totalH: Int, val width: Int)

    suspend fun combine(
        ctx: Context,
        uris: List<Uri>,
        targetW: Int,
        maxOutH: Int,
        jpegQ: Int,
        baseName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        require(uris.isNotEmpty()) { "Pilih minimal 1 gambar" }
        require(targetW in 240..2160) { "Lebar target 240–2160" }
        require(maxOutH in 2000..30000) { "Panjang maks 2000–30000" }

        // Src = object-level (lihat bawah file).
        val srcs = uris.map { u ->
            val info = ImageTiling.probe(ctx, u)
            Src(u, info.width, info.height, (info.height.toFloat() * targetW / info.width).toInt().coerceAtLeast(1))
        }
        val totalH = srcs.sumOf { it.scaledH }
        require(totalH > 0) { "Gambar tidak valid" }

        // Batas chunk agar bitmap chunk ≤ ~targetW×maxOutH.
        val chunks = (totalH + maxOutH - 1) / maxOutH
        val base = ZipKit.sanitize(baseName, "GrooxOCR_gabung")
        val files = mutableListOf<File>()
        // Offset tiap source dalam ruang target.
        val offs = mutableListOf<Int>()
        var acc = 0
        srcs.forEach { offs.add(acc); acc += it.scaledH }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (c in 0 until chunks) {
            onProgress(c, chunks)
            val cy0 = c * maxOutH
            val ch = minOf(maxOutH, totalH - cy0)
            var chunk = Bitmap.createBitmap(targetW, ch, Bitmap.Config.ARGB_8888)
            try {
                Canvas(chunk).drawColor(Color.WHITE)
                srcs.forEachIndexed { si, s ->
                    val sy0 = offs[si]
                    val sy1 = sy0 + s.scaledH
                    val oy0 = maxOf(cy0, sy0)
                    val oy1 = minOf(cy0 + ch, sy1)
                    if (oy1 - oy0 < 1) return@forEachIndexed
                    // Gambar per pita 2000px (ruang target) agar crop region kecil.
                    var bandTop = oy0
                    while (bandTop < oy1) {
                        val bandBot = minOf(oy1, bandTop + 2000)
                        drawBand(ctx, s, sy0, bandTop, bandBot, targetW, chunk, cy0, paint)
                        bandTop = bandBot
                    }
                }
                val f = File(ctx.cacheDir, "tmp_combine_${System.currentTimeMillis()}_$c.jpg")
                f.outputStream().use { chunk.compress(Bitmap.CompressFormat.JPEG, jpegQ, it) }
                files.add(f)
            } finally {
                chunk.recycle()
            }
        }
        onProgress(chunks, chunks)
        val named = ZipKit.ensureBaseNames(files, base)
        val zip = ZipKit.zip(named, File(ctx.cacheDir, "$base.zip"))
        return Result(named, zip, totalH, targetW)
    }

    /** Gambar satu pita dari source ke chunk via region-decode (hemat memori). */
    private fun drawBand(
        ctx: Context,
        s: Src,
        sy0: Int,
        bandTop: Int,
        bandBot: Int,
        targetW: Int,
        chunk: Bitmap,
        cy0: Int,
        paint: Paint,
    ) {
        val scale = targetW.toFloat() / s.w
        val srcY0 = ((bandTop - sy0) / scale).toInt().coerceIn(0, s.h - 1)
        val srcY1 = ((bandBot - sy0) / scale).toInt().coerceIn(srcY0 + 1, s.h)
        if (srcY1 - srcY0 < 1) return
        val rect = Rect(0, srcY0, s.w, srcY1)
        ctx.contentResolver.openInputStream(s.uri)?.use { ins ->
            val dec = BitmapRegionDecoder.newInstance(ins, false) ?: return
            try {
                var sample = 1
                while (s.w / (sample * 2) >= targetW && sample < 8) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                val crop = dec.decodeRegion(rect, opts) ?: return
                try {
                    val dst = android.graphics.RectF(
                        0f, (bandTop - cy0).toFloat(), targetW.toFloat(), (bandBot - cy0).toFloat()
                    )
                    Canvas(chunk).drawBitmap(crop, null, dst, paint)
                } finally {
                    crop.recycle()
                }
            } finally {
                dec.recycle()
            }
        }
    }

    private data class Src(val uri: Uri, val w: Int, val h: Int, val scaledH: Int)
}
