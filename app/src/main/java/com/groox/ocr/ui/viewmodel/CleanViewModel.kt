package com.groox.ocr.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.data.RecMode
import com.groox.ocr.engine.OcrEngine
import com.groox.ocr.image.CleanEngine
import com.groox.ocr.image.MiganMt
import com.groox.ocr.pdf.ZipKit
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CleanedImage(val uri: Uri, val bitmap: Bitmap, val w: Int, val h: Int)
data class CleanResult(val images: List<File>, val zip: File, val info: String)

sealed interface CleanUi {
    data object Idle : CleanUi
    data class Detecting(val done: Int, val total: Int) : CleanUi
    data class WordsReady(val items: List<CleanEngine.Detected>) : CleanUi
    data class Cleaning(val stage: String, val done: Int, val total: Int) : CleanUi
    data class PreviewReady(
        val items: List<CleanEngine.Detected>,
        val cleaned: List<CleanedImage>,
    ) : CleanUi
    data class Done(val result: CleanResult) : CleanUi
    data class Error(val message: String) : CleanUi
}

/**
 * Bersih: deteksi PP-OCR → pilih kata → inpaint (solid/gradasi/MiGAN) →
 * ZIP sesuai jumlah gambar + pratinjau.
 */
class CleanViewModel(
    appContext: Context,
    ocr: OcrEngine,
    migan: MiganMt,
) : ViewModel() {

    private val app = appContext.applicationContext
    private val engine = CleanEngine(app, ocr, migan)

    private val _ui = MutableStateFlow<CleanUi>(CleanUi.Idle)
    val ui: StateFlow<CleanUi> = _ui

    private val _images = MutableStateFlow<List<ImgItem>>(emptyList())
    val images: StateFlow<List<ImgItem>> = _images

    val recMode = MutableStateFlow(RecMode.AUTO)
    val method = MutableStateFlow(CleanEngine.Method.AUTO)
    val jpegQ = MutableStateFlow(92)
    val base = MutableStateFlow("GrooxOCR_bersih")

    private var job: Job? = null
    private var previewCache: List<CleanedImage> = emptyList()

    fun addImages(ctx: Context, uris: List<Uri>) {
        val cur = _images.value.toMutableList()
        for (u in uris) {
            if (cur.any { it.uri == u }) continue
            try {
                val info = ImageTiling.probe(ctx, u)
                cur.add(ImgItem(u, info.width, info.height))
            } catch (_: Exception) {}
        }
        _images.value = cur
    }

    fun removeImage(uri: Uri) {
        _images.value = _images.value.filterNot { it.uri == uri }
    }

    fun clearImages() {
        _images.value = emptyList()
        recyclePreview()
        _ui.value = CleanUi.Idle
    }

    fun backToWords() {
        val s = _ui.value
        if (s is CleanUi.PreviewReady) _ui.value = CleanUi.WordsReady(s.items)
    }

    fun cancel() {
        job?.cancel()
        _ui.value = CleanUi.Idle
    }

    // ---------- langkah 1: deteksi ----------

    fun runDetect() {
        val uris = _images.value.map { it.uri }
        if (uris.isEmpty()) return fail("Pilih gambar dulu")
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _ui.value = CleanUi.Detecting(0, uris.size)
                val items = engine.detect(uris, recMode.value) { d, t ->
                    _ui.value = CleanUi.Detecting(d, t)
                }
                _ui.value = CleanUi.WordsReady(items)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = CleanUi.Error(t.message ?: t.toString())
            }
        }
    }

    fun toggleWord(imageIdx: Int, wordId: Int) {
        val s = _ui.value as? CleanUi.WordsReady ?: return
        s.items.forEach { det ->
            det.words.forEach { w ->
                if (w.imageIdx == imageIdx && w.id == wordId) w.selected = !w.selected
            }
        }
        _ui.value = CleanUi.WordsReady(s.items.toList())
    }

    fun selectAll(imageIdx: Int, sel: Boolean) {
        val current = currentWords() ?: return
        current.items.forEach { det ->
            det.words.forEach { w -> if (w.imageIdx == imageIdx) w.selected = sel }
        }
        _ui.value = CleanUi.WordsReady(current.items.toList())
    }

    private fun currentWords(): CleanUi.WordsReady? = when (val s = _ui.value) {
        is CleanUi.WordsReady -> s
        is CleanUi.PreviewReady -> CleanUi.WordsReady(s.items)
        else -> null
    }

    // ---------- langkah 2: pratinjau / proses ----------

    private suspend fun cleanAll(
        items: List<CleanEngine.Detected>,
        forPreview: Boolean,
        onStage: (String, Int, Int) -> Unit,
    ): List<CleanedImage> {
        val out = mutableListOf<CleanedImage>()
        items.forEachIndexed { i, det ->
            onStage("Gambar ${i + 1}/${items.size}", i, items.size)
            val full = engine.clean(det, method.value, jpegQ.value) { _, _ -> }
            if (forPreview) {
                val sw = 720
                val s = sw.toFloat() / full.width
                val sh = (full.height * s).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(full, sw, sh, true)
                full.recycle()
                out.add(CleanedImage(det.uri, small, det.w, det.h))
            } else {
                out.add(CleanedImage(det.uri, full, det.w, det.h))
            }
        }
        onStage("Selesai", items.size, items.size)
        return out
    }

    fun runPreview() {
        val s = currentWords() ?: return fail("Deteksi dulu")
        job?.cancel()
        job = viewModelScope.launch {
            try {
                recyclePreview()
                _ui.value = CleanUi.Cleaning("Pratinjau…", 0, 1)
                val cleaned = cleanAll(s.items, true) { st, d, t ->
                    _ui.value = CleanUi.Cleaning(st, d, t)
                }
                previewCache = cleaned
                _ui.value = CleanUi.PreviewReady(s.items, cleaned)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = CleanUi.Error(t.message ?: t.toString())
            }
        }
    }

    fun runProcess() {
        val s = currentWords() ?: return fail("Deteksi dulu")
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _ui.value = CleanUi.Cleaning("Membersihkan…", 0, s.items.size)
                val cleaned = cleanAll(s.items, false) { st, d, t ->
                    _ui.value = CleanUi.Cleaning(st, d, t)
                }
                val b = ZipKit.sanitize(base.value, "GrooxOCR_bersih")
                val files = mutableListOf<File>()
                cleaned.forEachIndexed { i, c ->
                    val f = File(app.cacheDir, "tmp_clean_${System.currentTimeMillis()}_$i.jpg")
                    f.outputStream().use { c.bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQ.value, it) }
                    c.bitmap.recycle()
                    files.add(f)
                }
                val named = ZipKit.ensureBaseNames(files, b)
                val zip = ZipKit.zip(named, File(app.cacheDir, "$b.zip"))
                _ui.value = CleanUi.Done(
                    CleanResult(named, zip, "${named.size} gambar = ${s.items.size} input")
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = CleanUi.Error(t.message ?: t.toString())
            }
        }
    }

    /** Terapkan nama terbaru (file + ZIP ulang). */
    fun resolve(r: CleanResult): CleanResult {
        val b = ZipKit.sanitize(base.value, "GrooxOCR_bersih")
        if (r.zip.nameWithoutExtension == b && r.images.all { it.exists() }) return r
        val named = ZipKit.ensureBaseNames(r.images.filter { it.exists() }, b)
        val zip = ZipKit.zip(named, File(named.firstOrNull()?.parentFile, "$b.zip"))
        val next = r.copy(images = named, zip = zip)
        if (_ui.value is CleanUi.Done) _ui.value = CleanUi.Done(next)
        return next
    }

    private fun recyclePreview() {
        try {
            previewCache.forEach { try { it.bitmap.recycle() } catch (_: Exception) {} }
        } catch (_: Exception) {}
        previewCache = emptyList()
        val s = _ui.value
        if (s is CleanUi.PreviewReady) {
            try {
                s.cleaned.forEach { try { it.bitmap.recycle() } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }
    }

    private fun fail(msg: String) {
        _ui.value = CleanUi.Error(msg)
    }

    override fun onCleared() {
        recyclePreview()
        super.onCleared()
    }
}
