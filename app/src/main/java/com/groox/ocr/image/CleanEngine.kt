package com.groox.ocr.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.data.OcrParams
import com.groox.ocr.data.RecMode
import com.groox.ocr.engine.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orkestrasi Bersih: deteksi PP-OCR (tiling, bahasa pilih) → pecah kata →
 * klasifikasi background → inpaint per kata terpilih → bitmap penuh utuh.
 */
class CleanEngine(
    private val appContext: Context,
    private val ocr: OcrEngine,
    private val migan: MiganMt,
) {

    enum class Method(val label: String) {
        AUTO("Otomatis (solid/gradasi/MiGAN)"),
        SOLID("Paksa solid"),
        GRADIENT("Paksa gradasi"),
        MIGAN("Paksa MiGAN"),
    }

    data class Word(
        val id: Int,
        val imageIdx: Int,
        val rect: RectF, // koordinat full-res
        val text: String,
        val kind: InpaintKit.BgKind,
        var selected: Boolean = true,
    )

    data class Detected(
        val uri: Uri,
        val w: Int,
        val h: Int,
        val words: List<Word>,
        val elapsedMs: Long,
    )

    /** Langkah 1: deteksi + kenali + pecah kata + klasifikasi. */
    suspend fun detect(
        uris: List<Uri>,
        recMode: RecMode,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Detected> = withContext(Dispatchers.Default) {
        require(uris.isNotEmpty()) { "Pilih minimal 1 gambar" }
        val params = OcrParams(recMode = recMode)
        val out = mutableListOf<Detected>()
        uris.forEachIndexed { idx, uri ->
            onProgress(idx, uris.size)
            val t0 = System.currentTimeMillis()
            val res = ocr.run(uri, params) { _, _, _ -> }
            val full = InpaintKit.decodeFull(appContext, uri)
            try {
                val words = mutableListOf<Word>()
                var wid = 0
                for (ln in res.lines) {
                    if (ln.text.isBlank()) continue
                    val parts = splitWords(ln.text)
                    if (parts.isEmpty()) continue
                    val segW = ln.rect.width() / parts.size
                    parts.forEachIndexed { k, wd ->
                        val r = RectF(
                            ln.rect.left + segW * k,
                            ln.rect.top,
                            (if (k == parts.size - 1) ln.rect.right else ln.rect.left + segW * (k + 1)),
                            ln.rect.bottom,
                        )
                        val kind = if (full != null) {
                            try {
                                InpaintKit.classify(full, r)
                            } catch (_: Exception) {
                                InpaintKit.BgKind.TEXTURED
                            }
                        } else InpaintKit.BgKind.TEXTURED
                        words.add(Word(wid++, idx, r, wd, kind, true))
                    }
                }
                out.add(Detected(uri, res.imageWidth, res.imageHeight, words, System.currentTimeMillis() - t0))
            } finally {
                full?.recycle()
            }
        }
        onProgress(uris.size, uris.size)
        out
    }

    /** Pecah baris jadi kata; CJK tanpa spasi → per karakter. */
    fun splitWords(line: String): List<String> {
        val bySpace = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (bySpace.size > 1) return bySpace
        val s = bySpace.firstOrNull() ?: return emptyList()
        if (s.length <= 2) return listOf(s)
        // Tanpa spasi (CJK): per karakter agar selektif.
        return s.map { it.toString() }
    }

    /**
     * Langkah 2: hapus kata terpilih pada bitmap penuh.
     * @return bitmap bersih (mutable, milik pemanggil).
     */
    suspend fun clean(
        det: Detected,
        method: Method,
        jpegQ: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Bitmap = withContext(Dispatchers.Default) {
        val full = InpaintKit.decodeFull(appContext, det.uri)
            ?: throw RuntimeException("Gambar tidak terbaca")
        try {
            val sel = det.words.filter { it.selected }
            sel.forEachIndexed { i, wd ->
                onProgress(i, sel.size.coerceAtLeast(1))
                try {
                    cleanWord(full, wd, method)
                } catch (t: Throwable) {
                    Log.w("CleanEngine", "kata '${wd.text}' gagal: $t")
                }
            }
            onProgress(sel.size.coerceAtLeast(1), sel.size.coerceAtLeast(1))
            // Kembalikan salinan agar bitmap aman di-recycle pemanggil.
            full.copy(Bitmap.Config.ARGB_8888, false) ?: full
        } catch (t: Throwable) {
            full.recycle()
            throw t
        }
    }

    private fun cleanWord(full: Bitmap, wd: Word, method: Method) {
        // Pad 2px agar tepi teks ikut bersih.
        val box = RectF(
            (wd.rect.left - 2).coerceAtLeast(0f),
            (wd.rect.top - 2).coerceAtLeast(0f),
            (wd.rect.right + 2).coerceAtMost(full.width.toFloat()),
            (wd.rect.bottom + 2).coerceAtMost(full.height.toFloat()),
        )
        val tm = InpaintKit.textMaskOf(full, box) ?: return
        val use = when (method) {
            Method.SOLID -> InpaintKit.BgKind.SOLID
            Method.GRADIENT -> InpaintKit.BgKind.GRADIENT
            Method.MIGAN -> InpaintKit.BgKind.TEXTURED
            Method.AUTO -> wd.kind
        }
        when (use) {
            InpaintKit.BgKind.SOLID -> InpaintKit.inpaintSolid(full, box, tm)
            InpaintKit.BgKind.GRADIENT -> InpaintKit.inpaintGradient(full, box, tm)
            InpaintKit.BgKind.TEXTURED -> cleanTextured(full, box, tm)
        }
    }

    /** MiGAN pada crop kata + margin; gagal → difusi gradien. */
    private fun cleanTextured(full: Bitmap, box: RectF, tm: InpaintKit.TextMask) {
        val m = 48
        val l = (box.left - m).toInt().coerceIn(0, full.width - 1)
        val t = (box.top - m).toInt().coerceIn(0, full.height - 1)
        val r = (box.right + m).toInt().coerceIn(l + 32, full.width)
        val b = (box.bottom + m).toInt().coerceIn(t + 32, full.height)
        val cw = r - l
        val ch = b - t
        try {
            val crop = IntArray(cw * ch)
            full.getPixels(crop, 0, cw, l, t, cw, ch)
            // Mask teks dalam koordinat crop (mask box di-offset).
            val bl = box.left.toInt().coerceIn(0, full.width - 1)
            val bt = box.top.toInt().coerceIn(0, full.height - 1)
            val ox = bl - l
            val oy = bt - t
            val cmask = BooleanArray(cw * ch)
            for (y in 0 until tm.h) {
                for (x in 0 until tm.w) {
                    if (tm.mask[y * tm.w + x]) {
                        val cx = ox + x
                        val cy = oy + y
                        if (cx in 0 until cw && cy in 0 until ch) cmask[cy * cw + cx] = true
                    }
                }
            }
            if (!cmask.any { it }) return
            val done = migan.inpaintCrop(crop, cw, ch, cmask)
            full.setPixels(done, 0, cw, l, t, cw, ch)
        } catch (t: Throwable) {
            Log.w("CleanEngine", "MiGAN gagal, fallback gradasi: $t")
            InpaintKit.inpaintGradient(full, box, tm)
        }
    }
}
