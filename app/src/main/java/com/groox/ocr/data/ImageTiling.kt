package com.groox.ocr.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.InputStream

/**
 * Long-strip aware image inspection + tiling.
 *
 * Manhwa strips are typically 720px wide and up to 16000+px tall.
 * Loading the whole bitmap as ARGB_8888 costs w*h*4 bytes
 * (≈46 MB for 720×16000) and risks OOM + exceeds GL texture limits.
 * Instead we read dimensions first, then decode per-tile via
 * [BitmapRegionDecoder] (supports JPEG/PNG/WebP on API 28+).
 */
object ImageTiling {

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mime: String?,
        val byteSize: Long,
    )

    data class Tile(val index: Int, val rect: Rect)

    /** Fast dimension probe without full decode. */
    fun probe(ctx: Context, uri: Uri): ImageInfo {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            BitmapFactory.decodeStream(ins, null, opts)
        }
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) {
                try { size = c.getLong(idx) } catch (_: Exception) {}
            }
        }
        val mime = ctx.contentResolver.getType(uri)
        require(opts.outWidth > 0 && opts.outHeight > 0) {
            "Tidak bisa membaca dimensi gambar. Format harus JPG/PNG/WebP."
        }
        return ImageInfo(opts.outWidth, opts.outHeight, mime, size)
    }

    /**
     * Split full image height into overlapping tiles.
     * Overlap prevents cutting bubble text at tile borders;
     * duplicates are removed later by cross-tile NMS.
     */
    fun planTiles(
        width: Int,
        height: Int,
        tileHeight: Int = 1600,
        overlap: Int = 200,
    ): List<Tile> {
        require(width > 0 && height > 0)
        if (height <= tileHeight) return listOf(Tile(0, Rect(0, 0, width, height)))
        val tiles = mutableListOf<Tile>()
        var y = 0
        var idx = 0
        while (y < height) {
            var y1 = y + tileHeight
            if (y1 >= height) y1 = height
            // Extend upward overlap except for first tile.
            val y0 = if (idx == 0) y else (y - overlap).coerceAtLeast(0)
            tiles.add(Tile(idx++, Rect(0, y0, width, y1)))
            if (y1 == height) break
            y += tileHeight - overlap
        }
        return tiles
    }

    /** Decode a single tile region. Caller must recycle the bitmap. */
    fun decodeTile(ctx: Context, uri: Uri, tile: Rect, sampleSize: Int = 1): android.graphics.Bitmap {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            return decodeWithRegion(ins, tile, sampleSize)
        }
        throw RuntimeException("Tidak bisa membuka gambar")
    }

    private fun decodeWithRegion(ins: InputStream, tile: Rect, sampleSize: Int): android.graphics.Bitmap {
        val decoder = BitmapRegionDecoder.newInstance(ins, false)
            ?: throw RuntimeException("BitmapRegionDecoder tidak mendukung format ini")
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
            decoder.decodeRegion(tile, opts)
                ?: throw RuntimeException("decodeRegion gagal")
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Downsample factor so tile width fits detection memory budget.
     * Detection resizes long side to ~1280 anyway; pre-downsampling huge
     * widths (>1440) saves RAM without accuracy loss.
     */
    fun sampleSizeForWidth(width: Int): Int {
        var s = 1
        var w = width
        while (w > 1440) { s *= 2; w /= 2 }
        return s
    }
}
