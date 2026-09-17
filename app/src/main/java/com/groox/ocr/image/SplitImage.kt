package com.groox.ocr.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.pdf.ZipKit
import java.io.File

/**
 * Pisah vertikal: satu/lebih gambar dipotong memanjang menjadi beberapa bagian.
 * Mode: jumlah bagian (2–10) atau tinggi per bagian px. Via region-decode
 * (tidak pernah full-decode strip raksasa). Hasil: JPG + ZIP.
 */
object SplitImage {

    data class Result(val images: List<File>, val zip: File)

    suspend fun split(
        ctx: Context,
        uris: List<Uri>,
        parts: Int?,
        segHPx: Int?,
        jpegQ: Int,
        baseName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        require(uris.isNotEmpty()) { "Pilih minimal 1 gambar" }
        require((parts != null) xor (segHPx != null)) { "Pilih mode jumlah / tinggi" }
        if (parts != null) require(parts in 2..10) { "Jumlah 2–10" }
        if (segHPx != null) require(segHPx in 500..16000) { "Tinggi 500–16000 px" }

        val base = ZipKit.sanitize(baseName, "GrooxOCR_pisah")
        val files = mutableListOf<File>()
        var done = 0
        uris.forEach { uri ->
            val info = ImageTiling.probe(ctx, uri)
            val bounds = mutableListOf<Pair<Int, Int>>()
            if (parts != null) {
                for (i in 0 until parts) {
                    val y0 = (info.height.toLong() * i / parts).toInt()
                    val y1 = (info.height.toLong() * (i + 1) / parts).toInt()
                    if (y1 - y0 > 0) bounds.add(y0 to y1)
                }
            } else {
                var y = 0
                while (y < info.height) {
                    val y1 = minOf(info.height, y + segHPx!!)
                    if (y1 - y > 0) bounds.add(y to y1)
                    y = y1
                }
            }
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val dec = BitmapRegionDecoder.newInstance(ins, false)
                    ?: throw RuntimeException("Format tidak didukung")
                try {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    bounds.forEach { (y0, y1) ->
                        val part = dec.decodeRegion(Rect(0, y0, info.width, y1), opts)
                            ?: throw RuntimeException("Gagal potong")
                        try {
                            val f = File(ctx.cacheDir, "tmp_split_${System.currentTimeMillis()}_${done}.jpg")
                            f.outputStream().use {
                                part.compress(Bitmap.CompressFormat.JPEG, jpegQ, it)
                            }
                            files.add(f)
                        } finally {
                            part.recycle()
                        }
                        done++
                        onProgress(done, done + 1)
                    }
                } finally {
                    dec.recycle()
                }
            }
        }
        require(files.isNotEmpty()) { "Tidak ada potongan" }
        onProgress(done, done)
        val named = ZipKit.ensureBaseNames(files, base)
        val zip = ZipKit.zip(named, File(ctx.cacheDir, "$base.zip"))
        return Result(named, zip)
    }
}
