package com.groox.ocr.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import com.groox.ocr.data.ImageTiling
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gambar → PDF. Satu gambar = satu halaman penuh (atau beberapa halaman
 * berurutan bila strip melebihi batas tinggi), gambar selalu fit-width.
 */
object ImageToPdf {

    enum class Quality(val label: String, val jpegQ: Int, val maxW: Int) {
        ORIGINAL("Original/tajam", 95, Int.MAX_VALUE),
        BALANCED("Seimbang", 85, 1080),
        SMALL("Hemat", 75, 720),
    }

    enum class PageWidth(val label: String, val pt: Float?) {
        W595("Lebar A4 (595pt)", 595f),
        W720("Lebar 720pt (1:1 manhwa)", 720f),
        ORIGINAL("Ikut lebar gambar", null),
    }

    data class Result(
        val file: File,
        val bytes: Long,
        val pages: Int,
        val images: Int,
        val locked: Boolean = false,
    )

    suspend fun convert(
        ctx: Context,
        uris: List<Uri>,
        quality: Quality,
        pageWidth: PageWidth,
        password: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        require(uris.isNotEmpty()) { "Pilih minimal 1 gambar" }
        val pages = mutableListOf<PdfWriter.JpegPage>()
        var pageWpt = 720f
        uris.forEachIndexed { idx, uri ->
            onProgress(idx, uris.size)
            val info = ImageTiling.probe(ctx, uri)
            val targetW = minOf(info.width, quality.maxW)
            val scale = targetW.toFloat() / info.width
            val targetH = (info.height * scale).toInt().coerceAtLeast(1)
            pageWpt = pageWidth.pt ?: targetW.toFloat()

            val mime = ctx.contentResolver.getType(uri) ?: ""
            val isJpeg = mime == "image/jpeg" || mime == "image/jpg"

            val one: PdfWriter.JpegPage? =
                if (isJpeg && quality == Quality.ORIGINAL && targetW == info.width) {
                    // Jalur tajam: byte JPEG asli tanpa re-encode.
                    val raw = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    PdfWriter.JpegPage(info.width, info.height, raw)
                } else {
                    val bmp = decodeScaled(ctx, uri, targetW)
                    if (bmp == null) null else try {
                        PdfWriter.JpegPage(bmp.width, bmp.height, PdfWriter.jpegBytes(bmp, quality.jpegQ))
                    } finally {
                        bmp.recycle()
                    }
                }
            if (one != null) {
                // Pecah bila halaman melebihi batas (re-encode segmen bila perlu).
                pages += PdfWriter.splitIfTall(one, pageWpt, quality.jpegQ)
            }
        }
        onProgress(uris.size, uris.size)
        require(pages.isNotEmpty()) { "Tidak ada gambar yang bisa diproses" }
        val pw = password?.takeIf { it.isNotEmpty() }
        val pdf = PdfWriter.build(pages, pageWpt, pw)
        val f = File(ctx.cacheDir, "GrooxOCR_${stamp()}.pdf")
        f.writeBytes(pdf)
        return Result(f, pdf.size.toLong(), pages.size, uris.size, pw != null)
    }

    /** Decode dengan downscale hemat memori bila target < asli. */
    private fun decodeScaled(ctx: Context, uri: Uri, targetW: Int): Bitmap? {
        // Sample-size dulu agar strip raksasa tidak meledak di memori.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outWidth > 0) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        return if (bmp.width != targetW) {
            val h = (bmp.height.toFloat() * targetW / bmp.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, h, true)
            bmp.recycle()
            scaled
        } else bmp
    }

    /** Decode satu segmen vertikal (dipakai bila perlu, hemat vs full-decode). */
    @Suppress("unused")
    fun decodeRegion(ctx: Context, uri: Uri, y0: Int, h: Int): Bitmap? {
        val info = ImageTiling.probe(ctx, uri)
        val rect = android.graphics.Rect(0, y0.coerceIn(0, info.height - 1), info.width, (y0 + h).coerceIn(1, info.height))
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val dec = BitmapRegionDecoder.newInstance(ins, false) ?: return null
            try {
                return dec.decodeRegion(
                    rect,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            } finally {
                dec.recycle()
            }
        }
        return null
    }

    fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
