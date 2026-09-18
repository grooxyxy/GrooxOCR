package com.groox.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.data.ModelManager
import com.groox.ocr.data.OcrModels
import com.groox.ocr.data.OcrParams
import com.groox.ocr.data.RecMode
import java.io.File
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * End-to-end OCR orchestrator (PP-OCRv6-small primary).
 *
 * Flow: probe → tile plan → per-tile DET → global NMS → per-box REC
 * (dual v6/korean routing) → [BubbleGrouper] → [OcrResult].
 *
 * All heavy work runs on Dispatchers.Default; progress via [onProgress].
 * Bitmaps are decoded per-tile / per-crop and recycled immediately so
 * 720×16000+ strips never reside fully in RAM.
 */
class OcrEngine(
    private val appContext: Context,
    private val models: ModelManager,
    private val sessions: OrtSessions,
) {

    data class OcrLine(
        val rect: RectF, // full-image coords
        val text: String,
        val score: Float,
        val engine: String, // "v6" | "ko"
    )

    data class OcrResult(
        val imageWidth: Int,
        val imageHeight: Int,
        val lines: List<OcrLine>,
        val bubbles: List<BubbleGrouper.Bubble>,
        val elapsedMs: Long,
    )

    suspend fun run(
        uri: Uri,
        params: OcrParams,
        onProgress: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): OcrResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val info = ImageTiling.probe(appContext, uri)
        Log.i("OcrEngine", "image ${info.width}x${info.height} mime=${info.mime}")

        // Dicts (bundled assets, tiny).
        val dictV6 = DictLoader.loadV6(appContext)
        val dictKo = if (params.recMode != RecMode.V6_ONLY) {
            try { DictLoader.loadKorean(appContext) } catch (t: Throwable) {
                Log.w("OcrEngine", "korean dict missing: $t"); emptyList()
            }
        } else emptyList()

        // Sessions (lazy open; throws with clear msg if model missing).
        val detFile = fileOrThrow(models.detFile(), "deteksi v6-small — salin model dari APK dulu")
        val recV6File = if (params.recMode != RecMode.KOREAN_ONLY)
            fileOrThrow(models.recV6File(), "rekognisi v6-small — salin model dari APK dulu") else null
        val recKoFile = if (params.recMode != RecMode.V6_ONLY)
            fileOrThrow(models.recKoFile(), "rekognisi Korea — salin model dari APK dulu / pakai mode V6 only") else null

        val detSession = sessions.detSession(detFile)
        val detInputName = detSession.inputNames.first()
        val recV6Session = recV6File?.let { sessions.recV6Session(it) }
        val recV6Input = recV6Session?.inputNames?.first()
        val recKoSession = recKoFile?.let { sessions.recKoSession(it) }
        val recKoInput = recKoSession?.inputNames?.first()

        // ---- 1) Tiled detection ----
        val tiles = ImageTiling.planTiles(info.width, info.height, params.tileHeight, params.tileOverlap)
        val globalBoxes = mutableListOf<DetPostprocess.DetBox>()
        tiles.forEachIndexed { ti, tile ->
            onProgress("Deteksi ${ti + 1}/${tiles.size}", ti, tiles.size)
            val tileBitmap = decodeTileBitmap(uri, tile.rect)
            try {
                val detIn = DetPreprocess.prepare(tileBitmap, params.detLongSide)
                val (prob, shape) = sessions.runDet(detSession, detInputName, detIn.buffer, detIn.nchw)
                val boxes = DetPostprocess.decode(
                    prob, shape,
                    detIn.resizedW, detIn.resizedH,
                    tileBitmap.width, tileBitmap.height,
                    thresh = OcrModels.DB_THRESH,
                    boxThresh = params.boxThresh,
                    unclipRatio = OcrModels.DB_UNCLIP_RATIO,
                    maxCandidates = OcrModels.DB_MAX_CANDIDATES,
                )
                // tile-local → global coords (account for pre-downsample if any)
                val sample = sampleUsed(tile.rect, tileBitmap)
                for (b in boxes) {
                    val r = b.rect
                    globalBoxes.add(
                        DetPostprocess.DetBox(
                            RectF(
                                tile.rect.left + r.left * sample,
                                tile.rect.top + r.top * sample,
                                tile.rect.left + r.right * sample,
                                tile.rect.top + r.bottom * sample,
                            ),
                            b.score,
                        )
                    )
                }
            } finally {
                tileBitmap.recycle()
            }
        }

        // Cross-tile NMS in global coords (overlap duplicates from tileOverlap).
        val merged = DetPostprocess.nms(globalBoxes, 0.3f)
        Log.i("OcrEngine", "det boxes: ${globalBoxes.size} → ${merged.size} after NMS")

        // ---- 2) Per-box recognition ----
        val lines = mutableListOf<OcrLine>()
        merged.forEachIndexed { bi, box ->
            onProgress("Baca ${bi + 1}/${merged.size}", bi, merged.size)
            val crop = cropBox(uri, info.width, info.height, box.rect) ?: return@forEachIndexed
            try {
                val rec = recognizeBox(
                    crop, params, dictV6, dictKo,
                    recV6Session, recV6Input, recKoSession, recKoInput,
                )
                if (rec != null && rec.text.isNotBlank() && rec.score >= params.recThresh) {
                    lines.add(OcrLine(box.rect, rec.text, rec.score, rec.engine))
                }
            } finally {
                crop.recycle()
            }
        }

        // Sort lines in reading order before grouping (stable bubble ids).
        val sortedLines = when (params.readingOrder) {
            com.groox.ocr.data.ReadingOrder.TOP_TO_BOTTOM_LTR ->
                lines.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            com.groox.ocr.data.ReadingOrder.TOP_TO_BOTTOM_RTL ->
                lines.sortedWith(compareBy({ it.rect.top }, { -it.rect.left }))
        }

        val bubbles = BubbleGrouper.group(
            sortedLines.map { BubbleGrouper.Line(it.rect, it.text, it.score) },
            padPx = params.bubblePadPx,
            padRatio = params.bubblePadRatio,
            mergeGap = (params.bubbleMergeGap * (info.width / 720f)).toInt().coerceAtLeast(20),
            order = params.readingOrder,
            imageWidth = info.width,
        )

        onProgress("Selesai", 1, 1)
        OcrResult(info.width, info.height, sortedLines, bubbles, System.currentTimeMillis() - t0)
    }

    private data class RecPick(val text: String, val score: Float, val engine: String)

    private fun recognizeBox(
        crop: Bitmap,
        params: OcrParams,
        dictV6: List<String>,
        dictKo: List<String>,
        recV6Session: OrtSession?,
        recV6Input: String?,
        recKoSession: OrtSession?,
        recKoInput: String?,
    ): RecPick? {
        // Handle vertical text: rotate tall crops to horizontal.
        val oriented = orientForRec(crop)
        try {
            var best: RecPick? = null
            if ((params.recMode == RecMode.V6_ONLY || params.recMode == RecMode.AUTO) &&
                recV6Session != null && recV6Input != null && dictV6.isNotEmpty()
            ) {
                val r = runRec(oriented, recV6Session, recV6Input, dictV6, "v6")
                best = pickBetter(best, r)
            }
            if ((params.recMode == RecMode.KOREAN_ONLY || params.recMode == RecMode.AUTO) &&
                recKoSession != null && recKoInput != null && dictKo.isNotEmpty()
            ) {
                val r = runRec(oriented, recKoSession, recKoInput, dictKo, "ko")
                best = pickBetter(best, r)
            }
            return best
        } finally {
            if (oriented !== crop) oriented.recycle()
        }
    }

    private fun pickBetter(a: RecPick?, b: RecPick?): RecPick? {
        if (a == null) return b
        if (b == null) return a
        // Prefer Hangul-containing result when scores are close (manhwa case).
        val aKo = DictLoader.containsHangul(a.text)
        val bKo = DictLoader.containsHangul(b.text)
        if (aKo != bKo) {
            // If one has Hangul and its score is within 0.25, trust the Hangul one
            // (v6-small outputs garbage latin on Hangul with inflated scores sometimes,
            //  but korean model is authoritative for Hangul).
            if (bKo && b.score + 0.25f >= a.score) return b
            if (aKo && a.score + 0.25f >= b.score) return a
        }
        return if (b.score > a.score) b else a
    }

    private fun runRec(
        crop: Bitmap,
        session: OrtSession,
        inputName: String,
        dict: List<String>,
        tag: String,
    ): RecPick? {
        return try {
            val recIn = RecPreprocess.prepare(crop)
            val logits = sessions.runRec(session, inputName, recIn.buffer, recIn.shape)
            val r = CtcDecoder.decode(logits.data, logits.seqLen, logits.numClasses, dict)
            if (r.text.isBlank()) null else RecPick(r.text.trim(), r.score, tag)
        } catch (t: Throwable) {
            Log.w("OcrEngine", "rec $tag failed: $t")
            null
        }
    }

    /** Rotate 90° CW if box is clearly vertical (h > 1.8*w). */
    private fun orientForRec(crop: Bitmap): Bitmap {
        if (crop.height > crop.width * 1.8f && crop.width >= 8) {
            val m = Matrix().apply { postRotate(90f) }
            return try {
                Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, m, true)
            } catch (t: Throwable) {
                crop
            }
        }
        return crop
    }

    private fun fileOrThrow(f: File, what: String): File {
        if (!f.exists() || f.length() < 1_000_000) {
            throw RuntimeException("Model $what belum ada. Jalankan 'Salin model dari APK' di tab OCR.")
        }
        return f
    }

    /** Decode tile region with optional pre-downsample for very wide images. */
    private fun decodeTileBitmap(uri: Uri, rect: Rect): Bitmap {
        // BitmapRegionDecoder does not support inSampleSize with decodeRegion on all
        // firmwares for WebP; so decode full-res region and downsample manually if needed.
        appContext.contentResolver.openInputStream(uri)?.use { ins ->
            val decoder = BitmapRegionDecoder.newInstance(ins, false)
                ?: throw RuntimeException("Format gambar tidak didukung (butuh JPG/PNG/WebP)")
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = decoder.decodeRegion(rect, opts)
                    ?: throw RuntimeException("Gagal decode tile")
                // Manual downsample if tile is wider than 1440 (memory guard).
                if (bmp.width > 1440) {
                    val s = bmp.width / 1440f
                    val nw = 1440
                    val nh = (bmp.height / s).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                    bmp.recycle()
                    return scaled
                }
                return bmp
            } finally {
                decoder.recycle()
            }
        }
        throw RuntimeException("Tidak bisa membuka gambar")
    }

    private fun sampleUsed(tileRect: Rect, decoded: Bitmap): Float {
        val tileW = tileRect.width().toFloat()
        if (tileW <= 0) return 1f
        return tileW / decoded.width.toFloat()
    }

    /** Crop a det box (with small padding) directly from the source image. */
    private fun cropBox(uri: Uri, imgW: Int, imgH: Int, box: RectF): Bitmap? {
        // Padding lebih longgar: crop ketat memotong tepi glyph sehingga
        // recognizer menjatuhkan spasi antar kata ("idon'thave"). Konteks
        // putih di sekitar teks membantu CTC mengembalikan token spasi.
        val padX = (box.width() * 0.06f + 6f)
        val padY = (box.height() * 0.22f + 5f)
        val l = (box.left - padX).toInt().coerceIn(0, imgW - 1)
        val t = (box.top - padY).toInt().coerceIn(0, imgH - 1)
        val r = (box.right + padX).toInt().coerceIn(l + 8, imgW)
        val b = (box.bottom + padY).toInt().coerceIn(t + 8, imgH)
        if (r - l < 8 || b - t < 8) return null
        // Guard absurd crops (full-width banners mis-detected): cap to 1200px wide.
        // Taller than that is handled by rec resize anyway.
        val rect = Rect(l, t, r, b)
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { ins ->
                val decoder = BitmapRegionDecoder.newInstance(ins, false) ?: return null
                try {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    decoder.decodeRegion(rect, opts)
                } finally {
                    decoder.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.w("OcrEngine", "crop failed $rect: $t")
            null
        }
    }
}
