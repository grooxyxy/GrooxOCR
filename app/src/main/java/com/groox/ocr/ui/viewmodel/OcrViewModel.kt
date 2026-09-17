package com.groox.ocr.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.data.ModelManager
import com.groox.ocr.data.OcrParams
import com.groox.ocr.data.ReadingOrder
import com.groox.ocr.data.RecMode
import com.groox.ocr.engine.OcrEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PickedOcrImage(val uri: Uri, val info: ImageTiling.ImageInfo)

data class BatchItem(val uri: Uri, val result: OcrEngine.OcrResult)

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data class Working(val stage: String, val done: Int, val total: Int) : OcrUiState
    data class DoneBatch(val items: List<BatchItem>) : OcrUiState
    data class Error(val message: String) : OcrUiState
}

class OcrViewModel(
    private val models: ModelManager,
    private val engine: OcrEngine,
) : ViewModel() {

    val modelState: StateFlow<ModelManager.ModelState> = models.state

    private val _ui = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val ui: StateFlow<OcrUiState> = _ui

    private val _picked = MutableStateFlow<List<PickedOcrImage>>(emptyList())
    val picked: StateFlow<List<PickedOcrImage>> = _picked

    private val _params = MutableStateFlow(OcrParams())
    val params: StateFlow<OcrParams> = _params

    private var job: Job? = null

    fun setRecMode(m: RecMode) { _params.value = _params.value.copy(recMode = m) }
    fun setReadingOrder(o: ReadingOrder) { _params.value = _params.value.copy(readingOrder = o) }
    fun setDetLongSide(v: Int) { _params.value = _params.value.copy(detLongSide = v) }
    fun setBoxThresh(v: Float) { _params.value = _params.value.copy(boxThresh = v) }

    fun installModels(includeKorean: Boolean = true) {
        viewModelScope.launch {
            try {
                models.ensureModels(includeKorean)
            } catch (t: Throwable) {
                _ui.value = OcrUiState.Error(t.message ?: t.toString())
            }
        }
    }

    /** Tambah 1..N gambar (dipakai picker single maupun multi). Duplikat dilewati. */
    fun pickImages(ctx: Context, uris: List<Uri>) {
        val cur = _picked.value.toMutableList()
        var added = 0
        var failed = 0
        for (u in uris) {
            if (cur.any { it.uri == u }) continue
            try {
                cur.add(PickedOcrImage(u, ImageTiling.probe(ctx, u)))
                added++
            } catch (_: Exception) { failed++ }
        }
        _picked.value = cur
        if (added == 0 && failed > 0) {
            _ui.value = OcrUiState.Error("Tidak ada gambar valid (butuh JPG/PNG/WebP)")
        }
    }

    fun removePicked(uri: Uri) {
        _picked.value = _picked.value.filterNot { it.uri == uri }
    }

    fun clearPicked() { _picked.value = emptyList() }

    /** OCR semua gambar terpilih, berurutan (hemat memori). */
    fun runOcrBatch(ctx: Context) {
        val list = _picked.value
        if (list.isEmpty()) {
            _ui.value = OcrUiState.Error("Pilih minimal 1 gambar dulu")
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            try {
                // Model sudah dibundel di APK; install = salin dari assets (tanpa internet).
                models.ensureModels(includeKorean = _params.value.recMode != RecMode.V6_ONLY)
                val out = mutableListOf<BatchItem>()
                list.forEachIndexed { i, p ->
                    _ui.value = OcrUiState.Working("Gambar ${i + 1}/${list.size}: mulai…", i, list.size)
                    val res = engine.run(p.uri, _params.value) { stage, _, _ ->
                        _ui.value = OcrUiState.Working("Gambar ${i + 1}/${list.size}: $stage", i, list.size)
                    }
                    out.add(BatchItem(p.uri, res))
                }
                _ui.value = OcrUiState.DoneBatch(out)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = OcrUiState.Error(t.message ?: t.toString())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _ui.value = OcrUiState.Idle
    }

    /** Kembali ke daftar gambar (hasil batch dibuang). */
    fun backToList() { _ui.value = OcrUiState.Idle }
}
