package com.groox.ocr.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlin.math.sqrt

/**
 * Inpainting manual tanpa model & tanpa OpenCV (full offline):
 * - SOLID: isi warna median ring + feather tepi (setara Telea di area solid).
 * - GRADIEN: difusi Laplace coarse-to-fine — background bergradasi
 *   direkonstruksi mulus (Telea/NS gagal di mask besar karena blur).
 * - Klasifikasi otomatis per kata dari ring di sekeliling box.
 * - Mask presisi = piksel teks via Otsu (bukan seluruh box).
 */
object InpaintKit {

    enum class BgKind(val label: String) {
        SOLID("solid"), GRADIENT("gradasi"), TEXTURED("tekstur/MiGAN")
    }

    // ---------- util piksel ----------

    fun lumOf(px: Int): Int =
        ((77 * ((px shr 16) and 0xFF) + 150 * ((px shr 8) and 0xFF) + 29 * (px and 0xFF)) shr 8)

    /** Otsu threshold dari histogram luminansi. */
    fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = 0.0
        var thresh = 128
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thresh = i
            }
        }
        return thresh
    }

    /** Dilasi biner 3x3 sekali. */
    fun dilate(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = mask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) out[ny * w + nx] = true
                }
            }
        }
        return out
    }

    // ---------- mask teks ----------

    data class TextMask(val mask: BooleanArray, val w: Int, val h: Int, val darkText: Boolean)

    /**
     * Mask piksel teks di dalam [box] (koordinat bitmap penuh).
     * Teks gelap → lum < otsu; teks terang → lum > otsu. Didilasi 1px.
     */
    fun textMaskOf(full: Bitmap, box: RectF): TextMask? {
        val l = box.left.toInt().coerceIn(0, full.width - 1)
        val t = box.top.toInt().coerceIn(0, full.height - 1)
        val r = box.right.toInt().coerceIn(l + 1, full.width)
        val b = box.bottom.toInt().coerceIn(t + 1, full.height)
        val w = r - l
        val h = b - t
        if (w < 2 || h < 2 || w * h > 4_000_000) return null
        val px = IntArray(w * h)
        full.getPixels(px, 0, w, l, t, w, h)
        // Rata-rata ring dalam (tepi box) sebagai acuan background.
        var ringSum = 0L
        var ringN = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) {
                ringSum += lumOf(px[y * w + x])
                ringN++
            }
        }
        var allSum = 0L
        for (p in px) allSum += lumOf(p)
        val ringMean = if (ringN > 0) ringSum.toDouble() / ringN else 128.0
        val allMean = allSum.toDouble() / px.size
        // Teks = kelompok yang menyimpang dari background tepi.
        val darkText = allMean < ringMean
        val hist = IntArray(256)
        for (p in px) hist[lumOf(p)]++
        val th = otsu(hist, px.size)
        var mask = BooleanArray(w * h) { i ->
            val v = lumOf(px[i])
            if (darkText) v < th else v > th
        }
        // Keamanan: bila mask menutupi hampir semua/nyaris nol, pakai box penuh.
        val cov = mask.count { it }.toDouble() / mask.size
        if (cov > 0.85 || cov < 0.01) {
            mask = BooleanArray(w * h) { true }
        }
        mask = dilate(mask, w, h)
        return TextMask(mask, w, h, darkText)
    }

    // ---------- klasifikasi background ----------

    /**
     * Klasifikasi dari RING di sekeliling box (d=10px, di luar box):
     * solid bila std rendah; gradasi bila cocok bidang linear sisa kecil;
     * sisanya tekstur → MiGAN.
     */
    fun classify(full: Bitmap, box: RectF, ringD: Int = 10): BgKind {
        val l = (box.left - ringD).toInt().coerceIn(0, full.width - 1)
        val t = (box.top - ringD).toInt().coerceIn(0, full.height - 1)
        val r = (box.right + ringD).toInt().coerceIn(l + 1, full.width)
        val b = (box.bottom + ringD).toInt().coerceIn(t + 1, full.height)
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val vs = mutableListOf<Float>()
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var n = 0
        val il = box.left.toInt()
        val it = box.top.toInt()
        val ir = box.right.toInt()
        val ib = box.bottom.toInt()
        for (y in t until b step 2) {
            for (x in l until r step 2) {
                if (x in il..ir && y in it..ib) continue // dalam box → lewati
                val p = full.getPixel(x, y)
                xs.add(x.toFloat())
                ys.add(y.toFloat())
                vs.add(lumOf(p).toFloat())
                rs += (p shr 16) and 0xFF
                gs += (p shr 8) and 0xFF
                bs += p and 0xFF
                n++
            }
        }
        if (n < 24) return BgKind.TEXTURED
        val mean = vs.average().toFloat()
        var varSum = 0.0
        for (v in vs) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / n)
        val rStd = channelStd(full, l, t, r, b, il, it, ir, ib, 16)
        val gStd = channelStd(full, l, t, r, b, il, it, ir, ib, 8)
        val bStd = channelStd(full, l, t, r, b, il, it, ir, ib, 0)
        if (std < 7 && rStd < 7 && gStd < 7 && bStd < 7) return BgKind.SOLID
        // Cocokkan bidang lum ≈ a*x + b*y + c (least squares 3x3).
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        var sxv = 0.0
        var syv = 0.0
        var sx = 0.0
        var sy = 0.0
        var sv = 0.0
        val mx = xs.average()
        val my = ys.average()
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            val dv = vs[i] - mean
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
            sxv += dx * dv
            syv += dy * dv
            sx += dx
            sy += dy
            sv += dv
        }
        // Selesaikan normal equations 3x3 via Cramer kasar.
        val det = sxx * (syy * n - sy * sy) - sxy * (sxy * n - sy * sx) + sx * (sxy * sy - syy * sx)
        if (abs(det) > 1e-9) {
            val detA = sxv * (syy * n - sy * sy) - sxy * (syv * n - sy * sv) + sx * (syv * sy - syy * sv)
            val detB = sxx * (syv * n - sy * sv) - sxv * (sxy * n - sy * sx) + sx * (sxy * sv - syv * sx)
            val detC = sxx * (syy * sv - syv * sy) - sxy * (sxy * sv - syv * sx) + sxv * (sxy * sy - syy * sx)
            val a = detA / det
            val bb = detB / det
            val c = detC / det
            var res = 0.0
            for (i in xs.indices) {
                val d = (a * (xs[i] - mx) + bb * (ys[i] - my) + c + mean) - vs[i]
                res += d * d
            }
            val resStd = sqrt(res / n)
            val gradMag = sqrt(a * a + bb * bb) * sqrt((r - l).toDouble().let { it * it } + (b - t) * (b - t))
            if (resStd < 9 && (gradMag > 4 || std < 14)) return BgKind.GRADIENT
        } else if (std < 14) {
            return BgKind.GRADIENT
        }
        return BgKind.TEXTURED
    }

    private fun abs(d: Double) = if (d < 0) -d else d

    private fun channelStd(
        full: Bitmap, l: Int, t: Int, r: Int, b: Int,
        il: Int, it: Int, ir: Int, ib: Int, shift: Int,
    ): Double {
        var sum = 0L
        var sum2 = 0L
        var n = 0
        for (y in t until b step 2) {
            for (x in l until r step 2) {
                if (x in il..ir && y in it..ib) continue
                val v = (full.getPixel(x, y) shr shift) and 0xFF
                sum += v
                sum2 += v.toLong() * v
                n++
            }
        }
        if (n == 0) return 999.0
        val mean = sum.toDouble() / n
        return sqrt(sum2.toDouble() / n - mean * mean)
    }

    // ---------- SOLID ----------

    /** Isi mask dengan warna median ring + feather 2px di tepi mask. */
    fun inpaintSolid(full: Bitmap, box: RectF, tm: TextMask) {
        val l = box.left.toInt().coerceIn(0, full.width - 1)
        val t = box.top.toInt().coerceIn(0, full.height - 1)
        val w = tm.w
        val h = tm.h
        // Median warna ring dalam.
        val px = IntArray(w * h)
        full.getPixels(px, 0, w, l, t, w, h)
        val rs = mutableListOf<Int>()
        val gs = mutableListOf<Int>()
        val bs = mutableListOf<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) {
                val p = px[y * w + x]
                rs.add((p shr 16) and 0xFF)
                gs.add((p shr 8) and 0xFF)
                bs.add(p and 0xFF)
            }
        }
        if (rs.isEmpty()) return
        rs.sort()
        gs.sort()
        bs.sort()
        val fr = rs[rs.size / 2]
        val fg = gs[gs.size / 2]
        val fb = bs[bs.size / 2]
        val fill = -0x1000000 or (fr shl 16) or (fg shl 8) or fb
        // Jarak ke tepi mask (untuk feather): 0=luar,1=inti,2=pinggir-dalam.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!tm.mask[i]) continue
                var edge = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h || !tm.mask[ny * w + nx]) {
                        edge = true
                        break@loop
                    }
                }
                px[i] = if (edge) {
                    val o = px[i]
                    val orr = (o shr 16) and 0xFF
                    val og = (o shr 8) and 0xFF
                    val ob = o and 0xFF
                    -0x1000000 or (((orr + fr) / 2) shl 16) or (((og + fg) / 2) shl 8) or ((ob + fb) / 2)
                } else fill
            }
        }
        full.setPixels(px, 0, w, l, t, w, h)
    }

    // ---------- GRADIEN (difusi Laplace coarse-to-fine) ----------

    /**
     * Selesaikan ∇²u=0 di dalam mask dengan syarat batas nilai tetangga.
     * Piramida: downsample s.d. sisi-min ≤48, Jacobi kasar 300 iterasi,
     * upsample bilinear sebagai init, 80 iterasi per level. Per kanal.
     */
    fun inpaintGradient(full: Bitmap, box: RectF, tm: TextMask) {
        val l = box.left.toInt().coerceIn(0, full.width - 1)
        val t = box.top.toInt().coerceIn(0, full.height - 1)
        val w = tm.w
        val h = tm.h
        if (w < 4 || h < 4) {
            inpaintSolid(full, box, tm)
            return
        }
        val px = IntArray(w * h)
        full.getPixels(px, 0, w, l, t, w, h)
        data class Level(val w: Int, val h: Int, val img: Array<FloatArray>, val mask: BooleanArray)
        val levels = mutableListOf<Level>()
        var cw = w
        var ch = h
        var cimg = Array(3) { FloatArray(w * h) }
        for (k in 0 until w * h) {
            cimg[0][k] = ((px[k] shr 16) and 0xFF).toFloat()
            cimg[1][k] = ((px[k] shr 8) and 0xFF).toFloat()
            cimg[2][k] = (px[k] and 0xFF).toFloat()
        }
        var cmask = tm.mask.copyOf()
        levels.add(Level(cw, ch, cimg, cmask))
        while (minOf(cw, ch) / 2 >= 32 && levels.size < 5) {
            val nw = cw / 2
            val nh = ch / 2
            if (nw < 8 || nh < 8) break
            val nimg = Array(3) { FloatArray(nw * nh) }
            val nmask = BooleanArray(nw * nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var mc = 0
                    val acc = FloatArray(3)
                    for (dy in 0..1) for (dx in 0..1) {
                        val sx = (x * 2 + dx).coerceIn(0, cw - 1)
                        val sy = (y * 2 + dy).coerceIn(0, ch - 1)
                        if (cmask[sy * cw + sx]) mc++
                        for (c in 0..2) acc[c] += cimg[c][sy * cw + sx]
                    }
                    for (c in 0..2) nimg[c][y * nw + x] = acc[c] / 4f
                    nmask[y * nw + x] = mc >= 2
                }
            }
            cw = nw
            ch = nh
            cimg = nimg
            cmask = nmask
            levels.add(Level(cw, ch, cimg, cmask))
        }
        // Selesaikan dari kasar → halus.
        var init: Array<FloatArray>? = null
        var initW = 0
        var initH = 0
        for (li in levels.indices.reversed()) {
            val lv = levels[li]
            val cur = Array(3) { lv.img[it].copyOf() }
            if (init != null) {
                // Upsample init sebagai tebakan awal piksel mask.
                for (y in 0 until lv.h) {
                    for (x in 0 until lv.w) {
                        if (!lv.mask[y * lv.w + x]) continue
                        val fx = x.toFloat() * (initW - 1) / (lv.w - 1).coerceAtLeast(1)
                        val fy = y.toFloat() * (initH - 1) / (lv.h - 1).coerceAtLeast(1)
                        for (c in 0..2) cur[c][y * lv.w + x] = bilinearF(init[c], initW, initH, fx, fy)
                    }
                }
            }
            val iters = if (li == levels.size - 1) 300 else 80
            jacobi(cur, lv.mask, lv.w, lv.h, iters)
            init = cur
            initW = lv.w
            initH = lv.h
        }
        val fine = init ?: return
        for (k in 0 until w * h) {
            if (!tm.mask[k]) continue
            val r = fine[0][k].toInt().coerceIn(0, 255)
            val g = fine[1][k].toInt().coerceIn(0, 255)
            val b = fine[2][k].toInt().coerceIn(0, 255)
            px[k] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        full.setPixels(px, 0, w, l, t, w, h)
    }

    private fun jacobi(img: Array<FloatArray>, mask: BooleanArray, w: Int, h: Int, iters: Int) {
        if (w < 3 || h < 3) return
        val buf = Array(3) { FloatArray(w * h) }
        repeat(iters) {
            for (c in 0..2) {
                val src = img[c]
                val dst = buf[c]
                for (y in 0 until h) {
                    val yu = if (y > 0) y - 1 else y
                    val yd = if (y < h - 1) y + 1 else y
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (!mask[i]) {
                            dst[i] = src[i]
                            continue
                        }
                        val xl = if (x > 0) x - 1 else x
                        val xr = if (x < w - 1) x + 1 else x
                        dst[i] = (src[yu * w + x] + src[yd * w + x] + src[y * w + xl] + src[y * w + xr]) * 0.25f
                    }
                }
            }
            for (c in 0..2) img[c] = buf[c].copyOf()
        }
    }

    private fun bilinearF(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val v00 = a[y0 * w + x0]
        val v10 = a[y0 * w + x1]
        val v01 = a[y1 * w + x0]
        val v11 = a[y1 * w + x1]
        return (v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty
    }

    /** Decode bitmap penuh (mutable) — 1 gambar dalam satu waktu. */
    fun decodeFull(
        ctx: android.content.Context,
        uri: android.net.Uri,
    ): Bitmap? {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val b = BitmapFactory.decodeStream(
                ins, null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
            if (b != null) {
                return b.copy(Bitmap.Config.ARGB_8888, true) ?: b
            }
        }
        return null
    }
}
