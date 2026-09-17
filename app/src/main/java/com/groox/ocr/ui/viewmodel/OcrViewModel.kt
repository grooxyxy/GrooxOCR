package com.groox.ocr.ui.viewmodel

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

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data class ImagePicked(val uri: Uri, val info: ImageTiling.ImageInfo) : OcrUiState
    data class Working(val stage: String, val done: Int, val total: Int) : OcrUiState
    data class Done(val result: OcrEngine.OcrResult, val uri: Uri) : OcrUiState
    data class Error(val message: String) : OcrUiState
}

class OcrViewModel(
    private val models: ModelManager,
    private val engine: OcrEngine,
) : ViewModel() {

    val modelState: StateFlow<ModelManager.ModelState> = models.state

    private val _ui = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val ui: StateFlow<OcrUiState> = _ui

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

    fun pickImage(uri: Uri, info: ImageTiling.ImageInfo) {
        job?.cancel()
        _ui.value = OcrUiState.ImagePicked(uri, info)
    }

    fun runOcr(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                // Model sudah dibundel di APK; install = salin dari assets (tanpa internet).
                models.ensureModels(includeKorean = _params.value.recMode != RecMode.V6_ONLY)
                _ui.value = OcrUiState.Working("Mulai…", 0, 1)
                val res = engine.run(uri, _params.value) { stage, done, total ->
                    _uivalue(stage, done, total)
                }
                _ui.value = OcrUiState.Done(res, uri)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = OcrUiState.Error(t.message ?: t.toString())
            }
        }
    }

    private fun _uivalue(stage: String, done: Int, total: Int) {
        _ui.value = OcrUiState.Working(stage, done, total)
    }

    fun cancel() {
        job?.cancel()
        _ui.value = OcrUiState.Idle
    }

    fun backToImage(uri: Uri, info: ImageTiling.ImageInfo) {
        _ui.value = OcrUiState.ImagePicked(uri, info)
    }
}
