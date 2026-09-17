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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToInt

/**
 * Unwatermark — port setia dari `watermark remover html v1.4.0`
 * (resources/watermark.js). TIDAK diubah: rumus reverse-blend Fire,
 * ambang, perataan whole-pixel & subpixel otomatis, smoothing tepi.
 *
 * Cara kerja: watermark semi-transparan memenuhi I = αW + (1-α)B, maka
 * background dipulihkan B = (I − αW)/(1−α) memakai sampel watermark (W,α)
 * yang diposisikan user. Pengecualian jujur: filter noise-JPEG (surfaceBlur
 * berbasis filter CSS canvas, default MATI di referensi) tidak diport.
 *
 * Catatan presisi: bacaan di luar batas array berperilaku seperti JS
 * (undefined → NaN → tulis 0) — direplikasi via Double.NaN.
 */
object Unwatermark {

    /** 9 anchor seperti referensi (Top Left … Bottom Right). */
    enum class Anchor9(val label: String) {
        TL("Kiri atas"), TC("Tengah atas"), TR("Kanan atas"),
        CL("Kiri tengah"), C("Tengah"), CR("Kanan tengah"),
        BL("Kiri bawah"), BC("Tengah bawah"), BR("Kanan bawah"),
    }

    enum class PreviewBlend(val label: String) { NORMAL("Normal"), DIFFERENCE("Difference") }

    data class Opts(
        val anchor: Anchor9 = Anchor9.TR,
        /** Geseran user (px full-res), dari drag/stepper — seperti drag & arrow keys. */
        val offX: Float = 0f,
        val offY: Float = 0f,
        val alphaAdjust: Float = 1f, // 0.5..1.5
        val transparencyThreshold: Int = 3, // 0..255
        val opaqueThreshold: Int = 240, // 0..255
        val smoothEdges: Boolean = false,
        val adjustBrightness: Boolean = false, // hanya bersama smoothEdges
        val autoSubpixel: Boolean = false,
        val wholePxRadius: Int = 0, // 0 = mati
    )

    data class Result(val images: List<File>, val zip: File)

    // ---------- publik ----------

    suspend fun apply(
        ctx: Context,
        uris: List<Uri>,
        wmUri: Uri,
        opts: Opts,
        jpegQ: Int,
        baseName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        require(uris.isNotEmpty()) { "Pilih minimal 1 gambar" }
        val wm = decodeBitmap(ctx, wmUri, 1)
            ?: throw RuntimeException("Sampel watermark tidak terbaca")
        try {
            require(wm.width > 1 && wm.height > 1) { "Sampel watermark invalid" }
            val base = ZipKit.sanitize(baseName, "GrooxOCR_unwm")
            val files = mutableListOf<File>()
            uris.forEachIndexed { idx, uri ->
                onProgress(idx, uris.size)
                val info = ImageTiling.probe(ctx, uri)
                val full = decodeBitmap(ctx, uri, 1)
                    ?: throw RuntimeException("Gambar ${idx + 1} gagal dibaca")
                try {
                    val pos = basePos(info.width, info.height, wm.width, wm.height, opts.anchor)
                    processInto(full, wm, pos.first + opts.offX, pos.second + opts.offY, opts)
                    val f = File(ctx.cacheDir, "tmp_unwm_${System.currentTimeMillis()}_$idx.jpg")
                    f.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, jpegQ, it) }
                    files.add(f)
                } finally {
                    full.recycle()
                }
            }
            onProgress(uris.size, uris.size)
            val named = ZipKit.ensureBaseNames(files, base)
            val zip = ZipKit.zip(named, File(ctx.cacheDir, "$base.zip"))
            return Result(named, zip)
        } finally {
            wm.recycle()
        }
    }

    /** Pratinjau kecil (≤720px) + overlay posisi watermark. */
    suspend fun preview(
        ctx: Context,
        uri: Uri,
        wmUri: Uri?,
        opts: Opts,
        blend: PreviewBlend,
    ): Bitmap {
        val info = ImageTiling.probe(ctx, uri)
        var sample = 1
        while (info.width / (sample * 2) >= 720 && info.width > 0) sample *= 2
        val bmp = decodeBitmap(ctx, uri, sample)
            ?: throw RuntimeException("Pratinjau gagal")
        val s = bmp.width.toFloat() / info.width
        if (wmUri == null) return bmp
        val wm = decodeBitmap(ctx, wmUri, 1) ?: return bmp
        try {
            // Skala watermark proporsional terhadap skala pratinjau.
            val ww = (wm.width * s).toInt().coerceAtLeast(1)
            val wh = (wm.height * s).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(wm, ww, wh, true)
            try {
                val pos = basePos(info.width, info.height, wm.width, wm.height, opts.anchor)
                val px = (pos.first + opts.offX) * s
                val py = (pos.second + opts.offY) * s
                val cv = Canvas(bmp)
                if (blend == PreviewBlend.NORMAL) {
                    val p = Paint().apply { alpha = 140 }
                    cv.drawBitmap(small, px, py, p)
                } else {
                    // Difference: |gambar − wm| diperkuat di area overlay.
                    val iw = minOf(ww, bmp.width - px.toInt().coerceAtLeast(0))
                    val ih = minOf(wh, bmp.height - py.toInt().coerceAtLeast(0))
                    val ox = px.toInt().coerceIn(0, bmp.width - 1)
                    val oy = py.toInt().coerceIn(0, bmp.height - 1)
                    if (iw > 0 && ih > 0) {
                        val a = IntArray(iw * ih)
                        val bpx = IntArray(iw * ih)
                        bmp.getPixels(a, 0, iw, ox, oy, iw, ih)
                        small.getPixels(
                            bpx, 0, iw,
                            (ox - px).toInt().coerceAtLeast(0),
                            (oy - py).toInt().coerceAtLeast(0), iw, ih,
                        )
                        for (k in a.indices) {
                            val p1 = a[k]
                            val p2 = bpx[k]
                            val dr = abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
                            val dg = abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
                            val db = abs((p1 and 0xFF) - (p2 and 0xFF))
                            val m = minOf(255, (dr + dg + db) / 3 * 2)
                            a[k] = Color.rgb(m, m, m)
                        }
                        bmp.setPixels(a, 0, iw, ox, oy, iw, ih)
                    }
                }
                // Bingkai posisi.
                val fp = Paint().apply {
                    color = Color.YELLOW
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                cv.drawRect(px, py, px + ww, py + wh, fp)
                return bmp
            } finally {
                small.recycle()
            }
        } finally {
            wm.recycle()
        }
    }

    fun basePos(
        imgW: Int, imgH: Int, wmW: Int, wmH: Int, anchor: Anchor9,
    ): Pair<Float, Float> {
        val x = when (anchor) {
            Anchor9.TL, Anchor9.CL, Anchor9.BL -> 0f
            Anchor9.TC, Anchor9.C, Anchor9.BC -> ((imgW - wmW) / 2f).coerceAtLeast(0f)
            else -> (imgW - wmW).toFloat().coerceAtLeast(0f)
        }
        val y = when (anchor) {
            Anchor9.TL, Anchor9.TC, Anchor9.TR -> 0f
            Anchor9.CL, Anchor9.C, Anchor9.CR -> ((imgH - wmH) / 2f).coerceAtLeast(0f)
            else -> (imgH - wmH).toFloat().coerceAtLeast(0f)
        }
        return x to y
    }

    // ---------- inti (port setia) ----------

    private fun decodeBitmap(ctx: Context, uri: Uri, sample: Int): Bitmap? {
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample.coerceAtLeast(1)
                },
            )
        }
    }

    /** Tulis hasil unwatermark ke [full] tepat di region overlap. */
    private fun processInto(full: Bitmap, wm: Bitmap, wmXf: Float, wmYf: Float, o: Opts) {
        val imgW = full.width
        val imgH = full.height
        val wmW = wm.width
        val wmH = wm.height
        // parseInt() JS = trunc ke nol; wm_x % 1 menyimpan tanda.
        val x = wmXf.toInt()
        val xSub = (wmXf - x).toDouble()
        val y = wmYf.toInt()
        val ySub = (wmYf - y).toDouble()
        val w = minOf(x + wmW, imgW) - maxOf(x, 0)
        val h = minOf(y + wmH, imgH) - maxOf(y, 0)
        if (w <= 0 || h <= 0) return

        // Piksel region gambar (penuh, bukan pratinjau).
        val imgPx = IntArray(w * h)
        full.getPixels(imgPx, 0, w, maxOf(x, 0), maxOf(y, 0), w, h)
        // Piksel watermark (straight alpha dari decode).
        val wmFull = IntArray(wmW * wmH)
        wm.getPixels(wmFull, 0, wmW, 0, 0, wmW, wmH)
        // Kanvas watermark seukuran overlap, digeser subpixel (bilinear).
        val ox = -minOf(x, 0)
        val oy = -minOf(y, 0)
        val wmPx = IntArray(w * h)
        for (ry in 0 until h) {
            for (rx in 0 until w) {
                wmPx[ry * w + rx] = bilinear(wmFull, wmW, wmH, ox + rx - xSub, oy + ry - ySub)
            }
        }

        val alphaOf: (IntArray, Int) -> Double = { a, i ->
            if (i < 0 || i >= a.size) Double.NaN else ((a[i] ushr 24) and 0xFF).toDouble()
        }
        val chanOf: (IntArray, Int, Int) -> Double = { a, i, c ->
            if (i < 0 || i >= a.size) Double.NaN
            else when (c) {
                0 -> ((a[i] shr 16) and 0xFF).toDouble()
                1 -> ((a[i] shr 8) and 0xFF).toDouble()
                else -> (a[i] and 0xFF).toDouble()
            }
        }

        // edgeThreshold: 0.4 × alfa maks (sampling tiap 12 + quirk lompat baris).
        var edgeThreshold = 0.0
        run {
            var i = 3
            val total = w * h * 4
            while (i < total) {
                val px = i / 4
                if (px < wmPx.size) {
                    val a = alphaOf(wmPx, px)
                    if (!a.isNaN() && a > edgeThreshold) edgeThreshold = a
                }
                if (i % (w * 4) < 11) i += w * 4
                i += 12
            }
            edgeThreshold *= 0.4
        }

        val needAlign = o.autoSubpixel || o.wholePxRadius > 0
        var horiz = mutableListOf<Int>()
        var vert = mutableListOf<Int>()
        if (needAlign) {
            // Kumpulkan piksel tepi alfa yang terisolasi (±3).
            outer@ for (idx in 3 until w * h * 4 step 4) {
                val pxi = idx / 4
                val cx = pxi % w
                val cy = pxi / w
                if (abs(alphaOf(wmPx, pxi - 1) - alphaOf(wmPx, pxi)) > edgeThreshold) {
                    if (cx - 3 < 0 || cx + 3 >= w) continue
                    horiz.add(idx)
                }
                if (abs(alphaOf(wmPx, pxi - w) - alphaOf(wmPx, pxi)) > edgeThreshold) {
                    for (ii in -3..3) {
                        if (cy - 3 < 0 || cy + 3 >= h) continue@outer
                    }
                    vert.add(idx)
                }
            }
            // Desimasi >1000: simpan indeks ganjil (efek splice JS).
            if (horiz.size > 1000) horiz = horiz.filterIndexed { k, _ -> k % 2 == 1 }.toMutableList()
            if (vert.size > 1000) vert = vert.filterIndexed { k, _ -> k % 2 == 1 }.toMutableList()
            // Saring silang 5x5 terhadap sumbu lain.
            val hSnap = horiz.toList()
            val vSnap = vert.toList()
            fun cross(list: MutableList<Int>, other: List<Int>) {
                val snap = list.toList()
                val drop = mutableSetOf<Int>()
                for ((k, yy) in snap.withIndex()) {
                    var j = yy - 12 * (w + 1)
                    while (j < yy + 12 * (w + 1)) {
                        if (other.contains(j)) {
                            drop.add(k)
                            break
                        }
                        if (j % (w * 4) > yy % (w * 4) + 12) j += w * 4 - 28
                        j += 4
                    }
                }
                val kept = snap.filterIndexed { k, _ -> k !in drop }
                list.clear()
                list.addAll(kept)
            }
            cross(horiz, vSnap)
            cross(vert, hSnap)
            if (horiz.size > 64) horiz = horiz.filterIndexed { k, _ -> k % 2 == 1 }.toMutableList()
            if (vert.size > 64) vert = vert.filterIndexed { k, _ -> k % 2 == 1 }.toMutableList()
        }

        var imgPixelOffset = 0
        val imgW = w // referensi: img_w = w (tanpa margin ekstra)
        var wmWork = wmPx

        if (o.wholePxRadius > 0 && needAlign) {
            // Pencarian offset whole-pixel erreur terkecil (port setia,
            // termasuk quirk: selalu pola tetangga vertikal).
            data class OE(val error: Double, val x: Int, val y: Int)
            var best = OE(Double.POSITIVE_INFINITY, 0, 0)
            for (ox2 in 0..o.wholePxRadius * 2) {
                for (oy2 in 0..o.wholePxRadius * 2) {
                    var offsetError = 0.0
                    val offPx = 4 * (ox2 + oy2 * imgW)
                    for (axisList in listOf(horiz, vert)) {
                        for (ii in axisList) {
                            val ai = ii - 3
                            val nW = intArrayOf(ai - 8 * w, ai - 4 * w, ai, ai + 4 * w, ai + 8 * w)
                            val nI = intArrayOf(
                                ai - 8 * imgW + offPx, ai - 4 * imgW + offPx, ai + offPx,
                                ai + 4 * imgW + offPx, ai + 8 * imgW + offPx,
                            )
                            val pA = DoubleArray(5) { alphaOf(wmWork, nW[it]) }
                            var spotError = 0.0
                            for (c in 0..2) {
                                val pI = DoubleArray(5) { chanOf(imgPx, nI[it], c) }
                                val pW = DoubleArray(5) { chanOf(wmWork, nW[it], c) }
                                val uw = DoubleArray(5) { j ->
                                    val aI = 255.0 / (255.0 - pA[j])
                                    val aW = -pA[j] / (255.0 - pA[j])
                                    aI * pI[j] + aW * pW[j]
                                }
                                val outer = (uw[0] + uw[4]) / 2
                                for (j in 0..4) {
                                    val d = uw[j] - outer
                                    spotError += d * d
                                }
                            }
                            offsetError += spotError
                        }
                    }
                    if (offsetError < best.error) best = OE(offsetError, ox2, oy2)
                }
            }
            imgPixelOffset = 4 * (best.x + best.y * imgW)
        }

        if (o.autoSubpixel && o.wholePxRadius == 0 && needAlign) {
            // Port setia: best sub per sumbu dari 7-tap, median 50% tengah.
            val subX = mutableListOf<Double>()
            val subY = mutableListOf<Double>()
            for ((axis, list) in listOf('x' to horiz, 'y' to vert)) {
                for (ii in list) {
                    val ai = ii - 3
                    val n = if (axis == 'x') {
                        intArrayOf(ai - 12, ai - 8, ai - 4, ai, ai + 4, ai + 8, ai + 12)
                    } else {
                        intArrayOf(ai - 12 * w, ai - 8 * w, ai - 4 * w, ai, ai + 4 * w, ai + 8 * w, ai + 12 * w)
                    }
                    val pA = DoubleArray(7) { alphaOf(wmWork, n[it]) }
                    var bestSub = 0.0
                    var bestErr = Double.POSITIVE_INFINITY
                    for (c in 0..2) {
                        val pI = DoubleArray(5) { chanOf(imgPx, n[it + 1], c) }
                        val pW = DoubleArray(7) { chanOf(wmWork, n[it], c) }
                        var sub = -1.0
                        while (sub < 1.0) {
                            val wa = DoubleArray(5)
                            val waa = DoubleArray(5)
                            for (j in 0..4) {
                                if (sub > 0) {
                                    wa[j] = pW[j + 1] * (1 - sub) + pW[j + 2] * sub
                                    waa[j] = pA[j + 1] * (1 - sub) + pA[j + 2] * sub
                                } else {
                                    wa[j] = pW[j + 1] * (1 + sub) + pW[j] * (-sub)
                                    waa[j] = pA[j + 1] * (1 + sub) + pA[j] * (-sub)
                                }
                            }
                            val uw = DoubleArray(5) { j ->
                                val aI = 255.0 / (255.0 - waa[j])
                                val aW = -waa[j] / (255.0 - waa[j])
                                aI * pI[j] + aW * wa[j]
                            }
                            val outer = (uw[0] + uw[4]) / 2
                            var err = 0.0
                            for (j in 0..4) {
                                val d = uw[j] - outer
                                err += d * d
                            }
                            if (err < bestErr) {
                                bestErr = err
                                bestSub = sub
                            }
                            sub += 0.05
                        }
                    }
                    (if (axis == 'x') subX else subY).add(bestSub / 3.0)
                }
            }
            fun mid50(v: MutableList<Double>): Double {
                if (v.isEmpty()) return 0.0
                v.sort()
                val n = v.size
                var s = 0.0
                for (k in (n * 0.25).roundToInt() until (n * 0.75).roundToInt()) s += v[k]
                return s / (n / 2.0).roundToInt().coerceAtLeast(1)
            }
            val subXv = mid50(subX)
            val subYv = mid50(subY)
            // Render ulang watermark digeser (-sub_x, -sub_y), bilinear.
            val shifted = IntArray(w * h)
            for (ry in 0 until h) {
                for (rx in 0 until w) {
                    shifted[ry * w + rx] = bilinear(
                        wmFull, wmW, wmH,
                        ox + rx - xSub - subXv, oy + ry - ySub - subYv,
                    )
                }
            }
            wmWork = shifted
        }

        // Rumus Fire: B = (I − αW)/(1−α), α disesuaikan.
        val out = IntArray(w * h * 3)
        for (k in 0 until w * h) {
            out[k * 3] = imgPx[k] shr 16 and 0xFF
            out[k * 3 + 1] = imgPx[k] shr 8 and 0xFF
            out[k * 3 + 2] = imgPx[k] and 0xFF
        }
        val strideFix = (imgW - w) * 4 // = 0 (setia referensi)
        for (k in 0 until w * h) {
            val j = k + imgPixelOffset / 4 + (k / w) * (strideFix / 4)
            val aAdj = minOf(o.alphaAdjust * (((wmWork[k] ushr 24) and 0xFF).toDouble()), 255.0)
            if (aAdj > o.transparencyThreshold) {
                val aI = 255.0 / (255.0 - aAdj)
                val aW = -aAdj / (255.0 - aAdj)
                for (c in 0..2) {
                    val iv = if (j in 0 until w * h) {
                        when (c) {
                            0 -> (imgPx[j] shr 16) and 0xFF
                            1 -> (imgPx[j] shr 8) and 0xFF
                            else -> imgPx[j] and 0xFF
                        }.toDouble()
                    } else Double.NaN
                    val wv = when (c) {
                        0 -> (wmWork[k] shr 16) and 0xFF
                        1 -> (wmWork[k] shr 8) and 0xFF
                        else -> wmWork[k] and 0xFF
                    }.toDouble()
                    out[k * 3 + c] = clamp8(Math.round(aI * iv + aW * wv).toDouble())
                }
                if (aAdj > o.opaqueThreshold) {
                    // Smoothing piksel sangat opak dengan tetangga kiri.
                    val f1 = (aAdj - o.opaqueThreshold) / (255 - o.opaqueThreshold)
                    for (c in 0..2) {
                        val left = if (j - 1 >= 0 && j - 1 < w * h) {
                            out[(j - 1) * 3 + c].toDouble()
                        } else 0.0 // JS: undefined→NaN→0
                        val cur = out[k * 3 + c].toDouble()
                        out[k * 3 + c] = clamp8(Math.round(f1 * left + (1 - f1) * cur).toDouble())
                    }
                }
            }
        }

        if (o.smoothEdges) {
            smoothAndBrightness(imgPx, wmWork, w, h, out, edgeThreshold, o.adjustBrightness)
        }

        // Tulis kembali ke bitmap penuh.
        val res = IntArray(w * h)
        for (k in 0 until w * h) {
            res[k] = -0x1000000 or (out[k * 3] shl 16) or (out[k * 3 + 1] shl 8) or out[k * 3 + 2]
        }
        full.setPixels(res, 0, w, maxOf(x, 0), maxOf(y, 0), w, h)
    }

    // ---------- pasca (port setia) ----------

    private fun smoothAndBrightness(
        imgPx: IntArray, wmPx: IntArray, w: Int, h: Int,
        out: IntArray, edgeThreshold: Double, adjustBrightness: Boolean,
    ) {
        val mask = ByteArray(w * h)
        var i = w * 4 + 7
        var j = w + 1
        while (i < w * h * 4) {
            val a = { idx: Int ->
                val p = idx / 4
                if (p < 0 || p >= wmPx.size) Double.NaN else ((wmPx[p] ushr 24) and 0xFF).toDouble()
            }
            if (abs(a(i - 4) - a(i)) > edgeThreshold) {
                mask[j] = 255.toByte()
                if (j - 1 >= 0) mask[j - 1] = 255.toByte()
                if (j + 1 < mask.size) mask[j + 1] = 255.toByte()
            }
            if (abs(a(i - w * 4) - a(i)) > edgeThreshold) {
                mask[j] = 255.toByte()
                if (j - w >= 0) mask[j - w] = 255.toByte()
                if (j + w < mask.size) mask[j + w] = 255.toByte()
            }
            i += 4
            j++
        }
        var maskCopy = mask.copyOf()
        val imgCopy = out.copyOf()
        var more = true
        while (more) {
            more = false
            val second = maskCopy.copyOf()
            for (k in 0 until w * h) {
                if (second[k] == 255.toByte()) {
                    more = true
                    val top = k - w > 0 && second[k - w] != 255.toByte()
                    val left = k - 1 > 0 && second[k - 1] != 255.toByte()
                    val right = k + 1 > 0 && second[k + 1] != 255.toByte()
                    val bottom = k + w > 0 && second[k + w] != 255.toByte()
                    val cnt = (if (top) 1 else 0) + (if (bottom) 1 else 0) +
                        (if (left) 1 else 0) + (if (right) 1 else 0)
                    if (cnt > 1) {
                        for (cc in 0..2) {
                            var s = 0
                            if (top) s += imgCopy[(k - w) * 3 + cc]
                            if (left) s += imgCopy[(k - 1) * 3 + cc]
                            if (right) s += imgCopy[(k + 1) * 3 + cc]
                            if (bottom) s += imgCopy[(k + w) * 3 + cc]
                            imgCopy[k * 3 + cc] = s / cnt
                        }
                        maskCopy[k] = 0
                    }
                }
            }
        }
        // brightness() referensi — preseden operator disamakan persis:
        // (r<<1+r+g<<2+b)>>3 * Math.random()
        fun bright(r: Int, g: Int, b: Int): Double {
            val v = ((r shl (1 + r + g)) shl (2 + b)) shr 3
            return v * Math.random()
        }
        if (adjustBrightness) {
            for (k in 0 until w * h) {
                if (mask[k] == 255.toByte()) {
                    val f = bright(imgCopy[k * 3], imgCopy[k * 3 + 1], imgCopy[k * 3 + 2]) /
                        bright(out[k * 3], out[k * 3 + 1], out[k * 3 + 2])
                    for (cc in 0..2) {
                        out[k * 3 + cc] = clamp8(Math.round(out[k * 3 + cc] * f).toDouble())
                    }
                }
            }
        } else {
            for (k in 0 until w * h) {
                if (mask[k] == 255.toByte()) {
                    out[k * 3] = imgCopy[k * 3]
                    out[k * 3 + 1] = imgCopy[k * 3 + 1]
                    out[k * 3 + 2] = imgCopy[k * 3 + 2]
                }
            }
        }
    }

    // ---------- util ----------

    /** Bilinear straight-alpha; luar batas = transparan (seperti kanvas). */
    private fun bilinear(px: IntArray, ww: Int, wh: Int, fx: Double, fy: Double): Int {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = (fx - x0).coerceIn(0.0, 1.0)
        val ty = (fy - y0).coerceIn(0.0, 1.0)
        fun g(x: Int, y: Int, s: Int): Double {
            if (x < 0 || y < 0 || x >= ww || y >= wh) return 0.0
            val p = px[y * ww + x]
            return when (s) {
                0 -> ((p shr 16) and 0xFF).toDouble()
                1 -> ((p shr 8) and 0xFF).toDouble()
                2 -> (p and 0xFF).toDouble()
                else -> ((p ushr 24) and 0xFF).toDouble()
            }
        }
        fun mix(a: Double, b: Double, t: Double) = a * (1 - t) + b * t
        val r = mix(mix(g(x0, y0, 0), g(x0 + 1, y0, 0), tx), mix(g(x0, y0 + 1, 0), g(x0 + 1, y0 + 1, 0), tx), ty)
        val gg = mix(mix(g(x0, y0, 1), g(x0 + 1, y0, 1), tx), mix(g(x0, y0 + 1, 1), g(x0 + 1, y0 + 1, 1), tx), ty)
        val b = mix(mix(g(x0, y0, 2), g(x0 + 1, y0, 2), tx), mix(g(x0, y0 + 1, 2), g(x0 + 1, y0 + 1, 2), tx), ty)
        val a = mix(mix(g(x0, y0, 3), g(x0 + 1, y0, 3), tx), mix(g(x0, y0 + 1, 3), g(x0 + 1, y0 + 1, 3), tx), ty)
        return (a.roundToInt().coerceIn(0, 255) shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (gg.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)
    }

    private fun clamp8(d: Double): Int {
        if (d.isNaN()) return 0 // tiru Uint8ClampedArray JS
        return d.roundToInt().coerceIn(0, 255)
    }
}
