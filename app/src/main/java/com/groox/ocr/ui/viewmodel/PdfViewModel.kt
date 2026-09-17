package com.groox.ocr.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.pdf.ImageToPdf
import com.groox.ocr.pdf.PdfCompressor
import com.groox.ocr.pdf.PdfToJpg
import com.groox.ocr.pdf.ZipKit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PickedImage(val uri: Uri, val w: Int, val h: Int, val bytes: Long)

sealed interface PdfUiState {
    data object Idle : PdfUiState
    data class Working(val stage: String, val done: Int, val total: Int) : PdfUiState
    data class ConvertDone(val result: ImageToPdf.Result) : PdfUiState
    data class CompressDone(val result: PdfCompressor.Result) : PdfUiState
    data class ToJpgDone(val result: PdfToJpg.Result) : PdfUiState
    data class Error(val message: String) : PdfUiState
}

class PdfViewModel : ViewModel() {

    private val _images = MutableStateFlow<List<PickedImage>>(emptyList())
    val images: StateFlow<List<PickedImage>> = _images

    private val _ui = MutableStateFlow<PdfUiState>(PdfUiState.Idle)
    val ui: StateFlow<PdfUiState> = _ui

    private val _quality = MutableStateFlow(ImageToPdf.Quality.ORIGINAL)
    val quality: StateFlow<ImageToPdf.Quality> = _quality

    private val _pageWidth = MutableStateFlow(ImageToPdf.PageWidth.W720)
    val pageWidth: StateFlow<ImageToPdf.PageWidth> = _pageWidth

    private val _level = MutableStateFlow(PdfCompressor.Level.MEDIUM)
    val level: StateFlow<PdfCompressor.Level> = _level

    private val _render = MutableStateFlow(PdfToJpg.Render.TAJAM)
    val render: StateFlow<PdfToJpg.Render> = _render

    /** Password kunci PDF (kosong = tanpa kunci). */
    private val _lockPass = MutableStateFlow("")
    val lockPass: StateFlow<String> = _lockPass

    /** Nama file output (tanpa ekstensi) — semua output bisa di-rename. */
    private val _nameBase = MutableStateFlow("GrooxOCR")
    val nameBase: StateFlow<String> = _nameBase

    private var job: Job? = null

    fun setQuality(q: ImageToPdf.Quality) { _quality.value = q }
    fun setPageWidth(p: ImageToPdf.PageWidth) { _pageWidth.value = p }
    fun setLevel(l: PdfCompressor.Level) { _level.value = l }
    fun setRender(r: PdfToJpg.Render) { _render.value = r }
    fun setLockPass(s: String) { _lockPass.value = s.take(64) }
    fun setNameBase(s: String) { _nameBase.value = s.take(60) }

    fun addImages(ctx: Context, uris: List<Uri>) {
        val cur = _images.value.toMutableList()
        var added = 0
        for (u in uris) {
            if (cur.any { it.uri == u }) continue
            try {
                val info = ImageTiling.probe(ctx, u)
                cur.add(PickedImage(u, info.width, info.height, info.byteSize))
                added++
            } catch (_: Exception) {}
        }
        _images.value = cur
        if (added == 0 && uris.isNotEmpty()) {
            _ui.value = PdfUiState.Error("Tidak ada gambar valid (butuh JPG/PNG/WebP)")
        }
    }

    fun moveUp(i: Int) {
        val cur = _images.value.toMutableList()
        if (i in 1 until cur.size) {
            val t = cur[i - 1]; cur[i - 1] = cur[i]; cur[i] = t
            _images.value = cur
        }
    }

    fun moveDown(i: Int) {
        val cur = _images.value.toMutableList()
        if (i in 0 until cur.size - 1) {
            val t = cur[i + 1]; cur[i + 1] = cur[i]; cur[i] = t
            _images.value = cur
        }
    }

    fun removeAt(i: Int) {
        val cur = _images.value.toMutableList()
        if (i in cur.indices) { cur.removeAt(i); _images.value = cur }
    }

    fun clear() { _images.value = emptyList() }

    fun backToList() { _ui.value = PdfUiState.Idle }

    fun convert(ctx: Context) {
        job?.cancel()
        val uris = _images.value.map { it.uri }
        if (uris.isEmpty()) {
            _ui.value = PdfUiState.Error("Pilih gambar dulu")
            return
        }
        val appCtx = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = PdfUiState.Working("Membuat PDF…", 0, uris.size)
                val r = ImageToPdf.convert(
                    appCtx, uris, _quality.value, _pageWidth.value,
                    _lockPass.value.ifBlank { null },
                ) { d, t ->
                    _ui.value = PdfUiState.Working("Membuat PDF… ($d/$t)", d, t)
                }
                _nameBase.value = r.file.nameWithoutExtension
                _ui.value = PdfUiState.ConvertDone(r)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = PdfUiState.Error(t.message ?: t.toString())
            }
        }
    }

    fun compress(ctx: Context, pdfUri: Uri) {
        job?.cancel()
        val appCtx = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = PdfUiState.Working("Mengompres PDF…", 0, 1)
                val r = PdfCompressor.compress(
                    appCtx, pdfUri, _level.value,
                    _lockPass.value.ifBlank { null },
                ) { d, t ->
                    _ui.value = PdfUiState.Working("Render halaman $d/$t…", d, t)
                }
                _nameBase.value = r.file.nameWithoutExtension
                _ui.value = PdfUiState.CompressDone(r)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = PdfUiState.Error(t.message ?: t.toString())
            }
        }
    }

    fun pdfToJpg(ctx: Context, pdfUri: Uri) {
        job?.cancel()
        val appCtx = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = PdfUiState.Working("PDF → JPG…", 0, 1)
                val r = PdfToJpg.convert(
                    appCtx, pdfUri, _render.value,
                    _nameBase.value.ifBlank { "GrooxOCR_pdf_jpg" },
                ) { d, t ->
                    _ui.value = PdfUiState.Working("Render halaman $d/$t…", d, t)
                }
                _nameBase.value = r.zip.nameWithoutExtension
                _ui.value = PdfUiState.ToJpgDone(r)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = PdfUiState.Error(t.message ?: t.toString())
            }
        }
    }

    /** Terapkan nama terbaru ke file hasil PDF (rename fisik). */
    fun resolvePdfFile(f: File): File {
        val renamed = ZipKit.ensureName(f, _nameBase.value.ifBlank { "GrooxOCR" })
        // Segarkan state agar nama konsisten.
        when (val s = _ui.value) {
            is PdfUiState.ConvertDone ->
                if (s.result.file.absolutePath != renamed.absolutePath)
                    _ui.value = PdfUiState.ConvertDone(s.result.copy(file = renamed))
            is PdfUiState.CompressDone ->
                if (s.result.file.absolutePath != renamed.absolutePath)
                    _ui.value = PdfUiState.CompressDone(s.result.copy(file = renamed))
            else -> {}
        }
        return renamed
    }

    /** Terapkan nama terbaru ke hasil PDF→JPG (file + ZIP ulang bila berubah). */
    fun resolveJpg(r: PdfToJpg.Result): PdfToJpg.Result {
        val base = ZipKit.sanitize(_nameBase.value.ifBlank { "GrooxOCR_pdf_jpg" })
        if (r.zip.nameWithoutExtension == base) return r
        val named = ZipKit.ensureBaseNames(r.images.filter { it.exists() }, base)
        val zip = ZipKit.zip(named, File(named.firstOrNull()?.parentFile, "$base.zip"))
        val next = r.copy(images = named, zip = zip)
        if (_ui.value is PdfUiState.ToJpgDone) _ui.value = PdfUiState.ToJpgDone(next)
        return next
    }

    fun cancel() { job?.cancel(); _ui.value = PdfUiState.Idle }

    @Suppress("unused")
    fun resultFile(): File? = when (val s = _ui.value) {
        is PdfUiState.ConvertDone -> s.result.file
        is PdfUiState.CompressDone -> s.result.file
        else -> null
    }
}
