package com.groox.ocr.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.pdf.ZipKit
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Smart Watermark — penempatan otomatis yang menghindari area ramai/teks.
 *
 * Beda dari referensi web: skor memakai VARIANSI luminansi sel (bukan selisih
 * tepi piksel), kandidat dari grid sel adaptif, plus penalti gelembung
 * (area terang + sedikit tinta gelap = dialog). Mendukung watermark TEKS
 * (bukan cuma logo), mode ubin diagonal, dan pratinjau langsung.
 * Output: JPG + ZIP. Full offline.
 */
object Watermark {

    enum class Source(val label: String) { TEXT("Teks"), LOGO("Logo PNG") }
    enum class Mode(val label: String) {
        SMART("Smart (hindari ramai)"),
        CORNERS("4 sudut"),
        CENTER("Tengah"),
        TILE("Ubin diagonal"),
    }
    enum class Blend(val label: String) {
        NORMAL("Normal"), MULTIPLY("Multiply/gelap"), SCREEN("Screen/terang")
    }
    enum class Anchor(val label: String) {
        AUTO("Otomatis (smart)"),
        TL("Kiri atas"), TR("Kanan atas"),
        BL("Kiri bawah"), BR("Kanan bawah"),
        CENTER("Tengah"),
    }

    data class Opts(
        val source: Source = Source.TEXT,
        val text: String = "GrooxOCR",
        val textColor: Int = Color.WHITE,
        val logoUri: Uri? = null,
        val count: Int = 2,
        val sizePct: Int = 14, // % dari lebar gambar
        val opacity: Int = 70, // 10..100
        val rotation: Int = 0, // -45..45
        val marginPx: Int = 32,
        val mode: Mode = Mode.SMART,
        val blend: Blend = Blend.NORMAL,
        val avoidBubble: Boolean = true,
        val anchor: Anchor = Anchor.AUTO, // posisi pilihan user (mode Smart)
    )

    data class Result(val images: List<File>, val zip: File)

    suspend fun apply(
        ctx: Context,
        uris: List<Uri>,
        opts: Opts,
        jpegQ: Int,
        baseName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        require(uris.isNotEmpty()) { "Pilih minimal 1 gambar" }
        val logo = if (opts.source == Source.LOGO) {
            val u = opts.logoUri ?: throw RuntimeException("Pilih file logo dulu")
            ctx.contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it)
            } ?: throw RuntimeException("Logo tidak terbaca")
        } else null
        try {
            val base = ZipKit.sanitize(baseName, "GrooxOCR_wm")
            val files = mutableListOf<File>()
            uris.forEachIndexed { idx, uri ->
                onProgress(idx, uris.size)
                val raw = decodeFull(ctx, uri) ?: throw RuntimeException("Gambar ${idx + 1} gagal dibaca")
                // Bitmap hasil decode itu IMMUTABLE → salin mutable sebelum digambar.
                val bmp = raw.copy(Bitmap.Config.ARGB_8888, true) ?: throw RuntimeException("Bitmap gagal")
                raw.recycle()
                try {
                    drawOn(ctx, bmp, logo, opts, Random(System.currentTimeMillis() + idx))
                    val f = File(ctx.cacheDir, "tmp_wm_${System.currentTimeMillis()}_$idx.jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, it) }
                    files.add(f)
                } finally {
                    bmp.recycle()
                }
            }
            onProgress(uris.size, uris.size)
            val named = ZipKit.ensureBaseNames(files, base)
            val zip = ZipKit.zip(named, File(ctx.cacheDir, "$base.zip"))
            return Result(named, zip)
        } finally {
            logo?.recycle()
        }
    }

    /** Pratinjau: watermark di atas versi kecil gambar pertama (≤480px). */
    suspend fun preview(ctx: Context, uri: Uri, opts: Opts): Bitmap {
        val logo = if (opts.source == Source.LOGO && opts.logoUri != null) {
            ctx.contentResolver.openInputStream(opts.logoUri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } else null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480 && bounds.outWidth > 0) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sample
                    },
                )
            } ?: throw RuntimeException("Pratinjau gagal")
            val bmp = raw.copy(Bitmap.Config.ARGB_8888, true) ?: throw RuntimeException("Bitmap gagal")
            raw.recycle()
            drawOn(ctx, bmp, logo, opts, Random(7))
            return bmp
        } finally {
            logo?.recycle()
        }
    }

    // ---------- inti ----------

    private fun decodeFull(ctx: Context, uri: Uri): Bitmap? {
        // Downsample ringan bila super-lebar agar kanvas muat memori.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600 && bounds.outWidth > 0) sample *= 2
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                },
            )
        }
    }

    private data class Spot(val x: Float, val y: Float, val score: Double)

    private fun drawOn(ctx: Context, bmp: Bitmap, logo: Bitmap?, opts: Opts, rnd: Random) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val wmW = w * opts.sizePct / 100f
        val margin = opts.marginPx.toFloat()
        val spots: List<Spot> = when (opts.mode) {
            Mode.CORNERS -> listOf(
                Spot(margin + wmW / 2, margin + wmW / 2, 0.0),
                Spot(w - margin - wmW / 2, margin + wmW / 2, 0.0),
                Spot(margin + wmW / 2, h - margin - wmW / 2, 0.0),
                Spot(w - margin - wmW / 2, h - margin - wmW / 2, 0.0),
            )
            Mode.CENTER -> listOf(Spot(w / 2, h / 2, 0.0))
            Mode.TILE -> tileSpots(w, h, wmW, opts.rotation)
            Mode.SMART -> if (opts.anchor == Anchor.AUTO) {
                smartSpots(bmp, wmW, margin, opts.count.coerceIn(1, 8), opts.avoidBubble)
            } else {
                anchorSpots(w, h, wmW, margin, opts.count.coerceIn(1, 8), opts.anchor)
            }
        }
        val cv = Canvas(bmp)
        spots.forEach { s ->
            drawOne(cv, bmp, logo, opts, s.x, s.y, wmW)
        }
    }

    /** Ubin diagonal menutupi seluruh gambar. */
    private fun tileSpots(w: Float, h: Float, wmW: Float, rotation: Int): List<Spot> {
        val step = wmW * 2.2f
        val out = mutableListOf<Spot>()
        var row = 0
        var y = step / 2
        // Rotasi ubin mengikuti sudut watermark agar sejajar.
        val rad = Math.toRadians(rotation.toDouble())
        val dx = (kotlin.math.cos(rad) * step).toFloat()
        val dy = (kotlin.math.sin(rad) * step).toFloat()
        while (y < h + step) {
            var x = step / 2 + if (row % 2 == 1) step / 2 else 0f
            while (x < w + step) {
                // Geser diagonal: baris miring mengikuti rotasi.
                out.add(Spot(x + row * dy * 0.4f, y, 0.0))
                x += step
            }
            y += step
            row++
        }
        return out
    }

    /** Posisi pilihan user: mulai dari anchor, sisanya menyebar sudut lain + tengah. */
    private fun anchorSpots(
        w: Float, h: Float, wmW: Float, margin: Float, count: Int, anchor: Anchor,
    ): List<Spot> {
        fun px(ax: Float, ay: Float) = Spot(
            (margin + wmW / 2 + ax * (w - 2 * margin - wmW)).coerceIn(0f, w),
            (margin + wmW / 2 + ay * (h - 2 * margin - wmW)).coerceIn(0f, h),
            0.0,
        )
        // Urutan sudut diputar agar anchor selalu pertama.
        val corners = when (anchor) {
            Anchor.TR -> listOf(1f to 0f, 0f to 0f, 1f to 1f, 0f to 1f)
            Anchor.BL -> listOf(0f to 1f, 0f to 0f, 1f to 1f, 1f to 0f)
            Anchor.BR -> listOf(1f to 1f, 1f to 0f, 0f to 1f, 0f to 0f)
            Anchor.CENTER -> emptyList()
            else -> listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f) // TL/AUTO
        }
        if (anchor == Anchor.CENTER) {
            // Tengah + variasi kecil agar count>1 tidak menumpuk.
            return List(count) { i ->
                val off = (i - (count - 1) / 2f) * wmW * 1.4f
                Spot((w / 2 + off).coerceIn(0f, w), h / 2, 0.0)
            }
        }
        val out = mutableListOf<Spot>()
        corners.forEach { (ax, ay) ->
            if (out.size < count) out.add(px(ax, ay))
        }
        while (out.size < count) out.add(px(0.5f, 0.5f))
        return out
    }

    /**
     * Smart placement berbasis VARIANSI: analisis bitmap kecil (≤360px),
     * bagi jadi sel, skor = variansi luminansi + penalti gelembung
     * (terang dominan + sedikit gelap = area dialog → hindari).
     * Ambil skor terkecil yang saling berjauhan.
     */
    private fun smartSpots(
        bmp: Bitmap, wmW: Float, margin: Float, count: Int, avoidBubble: Boolean,
    ): List<Spot> {
        val w = bmp.width
        val h = bmp.height
        val scale = 360f / w
        val sw = 360
        val sh = (h * scale).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        try {
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val lum = FloatArray(sw * sh) { i ->
                val c = px[i]
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            val cols = 9
            val rows = (9f * sh / sw).toInt().coerceIn(6, 60)
            val cw = sw.toFloat() / cols
            val ch = sh.toFloat() / rows
            data class Cell(val cx: Float, val cy: Float, val v: Double)
            val cells = mutableListOf<Cell>()
            for (ry in 0 until rows) {
                for (cx in 0 until cols) {
                    var sum = 0.0
                    var sum2 = 0.0
                    var n = 0
                    var bright = 0
                    var dark = 0
                    var y = (ry * ch).toInt()
                    while (y < minOf(sh, ((ry + 1) * ch).toInt())) {
                        var x = (cx * cw).toInt()
                        while (x < minOf(sw, ((cx + 1) * cw).toInt())) {
                            val l = lum[y * sw + x].toDouble()
                            sum += l; sum2 += l * l; n++
                            if (l > 205) bright++
                            if (l < 80) dark++
                            x += 2
                        }
                        y += 2
                    }
                    if (n == 0) continue
                    val mean = sum / n
                    var score = (sum2 / n - mean * mean) / 65025.0 // variansi ternormalisasi
                    if (avoidBubble) {
                        val br = bright.toDouble() / n
                        val dr = dark.toDouble() / n
                        // Gelembung dialog: terang dominan + sedikit tinta.
                        if (br > 0.45 && dr > 0.006) score += 2.0 + br
                    }
                    cells.add(Cell((cx + 0.5f) * cw / scale, (ry + 0.5f) * ch / scale, score))
                }
            }
            cells.sortBy { it.v }
            val minDist = maxOf(wmW * 0.9f, w * 0.18f)
            val picked = mutableListOf<Pair<Float, Float>>()
            for (c in cells) {
                val x = c.cx.coerceIn(margin + wmW / 2, w - margin - wmW / 2)
                val y = c.cy.coerceIn(margin + wmW / 2, h - margin - wmW / 2)
                if (w - 2 * margin - wmW < 0 || h - 2 * margin - wmW < 0) {
                    picked.add(w / 2f to h / 2f)
                    break
                }
                if (picked.all { hypot((it.first - x).toDouble(), (it.second - y).toDouble()) > minDist }) {
                    picked.add(x to y)
                    if (picked.size >= count) break
                }
            }
            while (picked.size < count && cells.isNotEmpty()) {
                val c = cells[picked.size % cells.size]
                picked.add(
                    c.cx.coerceIn(margin, w - margin) to
                        c.cy.coerceIn(margin, h - margin)
                )
            }
            return picked.map { Spot(it.first, it.second, 0.0) }
        } finally {
            small.recycle()
        }
    }

    private fun drawOne(
        cv: Canvas, bmp: Bitmap, logo: Bitmap?, opts: Opts, cx: Float, cy: Float, wmW: Float,
    ) {
        val alpha = (opts.opacity.coerceIn(10, 100) * 255 / 100)
        cv.save()
        cv.rotate(opts.rotation.toFloat(), cx, cy)
        if (opts.source == Source.LOGO && logo != null) {
            val scale = wmW / logo.width
            val dw = wmW
            val dh = logo.height * scale
            val dst = RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                this.alpha = alpha
                when (opts.blend) {
                    Blend.MULTIPLY -> xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    Blend.SCREEN -> xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    else -> {}
                }
            }
            cv.drawBitmap(logo, null, dst, p)
        } else {
            val text = opts.text.ifBlank { "GrooxOCR" }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = opts.textColor
                this.alpha = alpha
                textSize = wmW * 0.42f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                setShadowLayer(wmW * 0.03f, 0f, wmW * 0.02f, Color.argb(alpha, 0, 0, 0))
            }
            // Latar pill semi-transparan agar teks terbaca di area ramai.
            val tw = p.measureText(text)
            val pill = RectF(
                cx - tw / 2 - wmW * 0.12f, cy - wmW * 0.32f,
                cx + tw / 2 + wmW * 0.12f, cy + wmW * 0.30f,
            )
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.45f).toInt(), 0, 0, 0)
            }
            val rad = wmW * 0.16f
            cv.drawRoundRect(pill, rad, rad, bg)
            val fm = p.fontMetrics
            cv.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, p)
        }
        cv.restore()
    }

    /** Warna teks siap pakai. */
    val TEXT_COLORS = listOf(
        "Putih" to Color.WHITE,
        "Hitam" to Color.BLACK,
        "Kuning" to Color.YELLOW,
        "Cyan" to Color.CYAN,
        "Merah" to Color.RED,
    )
}
