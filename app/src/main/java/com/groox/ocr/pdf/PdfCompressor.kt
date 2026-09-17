package com.groox.ocr.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Kompres PDF yang sudah ada — full offline tanpa dependensi.
 * Tiap halaman di-render via framework [PdfRenderer] pada lebar target,
 * lalu dibangun ulang sebagai PDF JPEG (fit-width, bisa di-scroll).
 * Efektif untuk PDF hasil scan/komik yang bengkak.
 */
object PdfCompressor {

    enum class Level(val label: String, val width: Int, val jpegQ: Int) {
        LIGHT("Ringan (1080px, q85)", 1080, 85),
        MEDIUM("Sedang (720px, q75)", 720, 75),
        STRONG("Kuat (720px, q60)", 720, 60),
    }

    data class Result(
        val file: File,
        val outBytes: Long,
        val inBytes: Long,
        val pages: Int,
    ) {
        val ratio: Float get() = if (inBytes <= 0) 0f else outBytes.toFloat() / inBytes
    }

    suspend fun compress(
        ctx: Context,
        pdfUri: Uri,
        level: Level,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ctx.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: throw RuntimeException("Tidak bisa membuka PDF")
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            require(count > 0) { "PDF kosong" }
            val inBytes = ctx.contentResolver.openInputStream(pdfUri)?.use {
                it.readBytes().size.toLong()
            } ?: -1L

            val pages = mutableListOf<PdfWriter.JpegPage>()
            val pageWpt = level.width.toFloat()
            for (i in 0 until count) {
                onProgress(i, count)
                val page = renderer.openPage(i)
                try {
                    val scale = level.width.toFloat() / page.width
                    val rw = level.width
                    val rh = (page.height * scale).toInt().coerceAtLeast(1)
                    var bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                    try {
                        val m = Matrix().apply { postScale(scale, scale) }
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages += PdfWriter.splitIfTall(
                            PdfWriter.JpegPage(rw, rh, PdfWriter.jpegBytes(bmp, level.jpegQ)),
                            pageWpt,
                            level.jpegQ,
                        )
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.close()
                }
            }
            onProgress(count, count)
            val pdf = PdfWriter.build(pages, pageWpt)
            val f = File(ctx.cacheDir, "GrooxOCR_compressed_${ImageToPdf.stamp()}.pdf")
            f.writeBytes(pdf)
            return Result(f, pdf.size.toLong(), inBytes, pages.size)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
