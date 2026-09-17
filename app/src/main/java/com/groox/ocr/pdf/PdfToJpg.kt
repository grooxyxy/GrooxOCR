package com.groox.ocr.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * PDF → JPG per halaman (full offline via framework PdfRenderer).
 * Output: file-file JPG + ZIP-nya; semuanya bisa di-rename.
 */
object PdfToJpg {

    enum class Render(val label: String, val width: Int, val jpegQ: Int) {
        HEMAT("Hemat (720px, q75)", 720, 75),
        TAJAM("Tajam (1080px, q90)", 1080, 90),
        ASLI("Besar (1440px, q95)", 1440, 95),
    }

    data class Result(
        val images: List<File>,
        val zip: File,
        val pages: Int,
    )

    suspend fun convert(
        ctx: Context,
        pdfUri: Uri,
        render: Render,
        baseName: String,
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
            val base = ZipKit.sanitize(baseName, "GrooxOCR_pdf_jpg")
            val files = mutableListOf<File>()
            for (i in 0 until count) {
                onProgress(i, count)
                val page = renderer.openPage(i)
                try {
                    val scale = render.width.toFloat() / page.width
                    val rw = render.width
                    val rh = (page.height * scale).toInt().coerceAtLeast(1)
                    var bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                    try {
                        val m = Matrix().apply { postScale(scale, scale) }
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(ctx.cacheDir, "tmp_${System.currentTimeMillis()}_$i.jpg")
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, render.jpegQ, it) }
                        files.add(f)
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.close()
                }
            }
            onProgress(count, count)
            val named = ZipKit.ensureBaseNames(files, base)
            val zip = ZipKit.zip(named, File(ctx.cacheDir, "$base.zip"))
            return Result(named, zip, count)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
