package com.groox.ocr.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.groox.ocr.mt.Translator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface TranslateUi {
    data object Idle : TranslateUi
    data class Working(val stage: String, val done: Int, val total: Int) : TranslateUi
    data class Done(val output: String) : TranslateUi
    data class Error(val message: String) : TranslateUi
}

class TranslateViewModel(private val translator: Translator) : ViewModel() {

    private val _ui = MutableStateFlow<TranslateUi>(TranslateUi.Idle)
    val ui: StateFlow<TranslateUi> = _ui

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private val _dir = MutableStateFlow(Translator.Direction.KO_EN)
    val dir: StateFlow<Translator.Direction> = _dir

    private var job: Job? = null

    fun setInput(s: String) { _input.value = s.take(20000) }
    fun setDir(d: Translator.Direction) { _dir.value = d }

    fun loadTxt(ctx: Context, uri: Uri) {
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw RuntimeException("Tidak bisa membaca file")
            _input.value = text.take(20000)
            _ui.value = TranslateUi.Idle
        } catch (t: Throwable) {
            _ui.value = TranslateUi.Error(t.message ?: t.toString())
        }
    }

    fun clear() {
        job?.cancel()
        _input.value = ""
        _ui.value = TranslateUi.Idle
    }

    fun backToEdit() { _ui.value = TranslateUi.Idle }

    fun translate() {
        val src = _input.value
        if (src.isBlank()) {
            _ui.value = TranslateUi.Error("Ketik/paste teks atau ambil dari file TXT dulu")
            return
        }
        job?.cancel()
        job = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                _ui.value = TranslateUi.Working("Memuat model…", 0, 1)
                val out = translator.translate(src, _dir.value) { d, t ->
                    _ui.value = TranslateUi.Working("Menerjemahkan baris $d/$t…", d, t)
                }
                _ui.value = TranslateUi.Done(out)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = TranslateUi.Error(t.message ?: t.toString())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _ui.value = TranslateUi.Idle
    }
}
