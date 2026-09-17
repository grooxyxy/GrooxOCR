package com.groox.ocr.data

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Model ter-bundel di APK (via GitHub Action).
 *
 * CI mengunduh 3 file ONNX ke `app/src/main/assets/models/` SEBELUM build,
 * sehingga APK sudah berisi model — TIDAK ada unduhan runtime, TIDAK butuh
 * INTERNET. Saat pertama dibuka, model disalin dari assets (APK) ke
 * [Context.filesDir]/models agar bisa dibuka sebagai file oleh ONNX Runtime.
 */
class ModelManager(private val appContext: Context) {

    data class ModelState(
        val detReady: Boolean = false,
        val recV6Ready: Boolean = false,
        val recKoReady: Boolean = false,
        val installing: String? = null,
        val progress: Float = 0f,
        val error: String? = null,
    ) {
        val allReady: Boolean get() = detReady && recV6Ready && recKoReady
        val primaryReady: Boolean get() = detReady && recV6Ready
    }

    private val _state = MutableStateFlow(ModelState())
    val state: StateFlow<ModelState> = _state

    fun modelsDir(): File = File(appContext.filesDir, "models").apply { mkdirs() }
    fun detFile(): File = File(modelsDir(), OcrModels.DET_FILE)
    fun recV6File(): File = File(modelsDir(), OcrModels.REC_V6_FILE)
    fun recKoFile(): File = File(modelsDir(), OcrModels.REC_KO_FILE)

    fun refresh() {
        _state.value = ModelState(
            detReady = valid(detFile(), OcrModels.DET_SIZE),
            recV6Ready = valid(recV6File(), OcrModels.REC_V6_SIZE),
            recKoReady = valid(recKoFile(), OcrModels.REC_KO_SIZE),
        )
    }

    private fun valid(f: File, expected: Long): Boolean {
        if (!f.exists()) return false
        val len = f.length()
        return len > expected * 0.9 && len < expected * 1.3 && len > 1_000_000
    }

    /**
     * Salin model yang hilang dari assets/models/ ke filesDir/models/.
     * Dipanggil sekali saat startup; tanpa internet.
     */
    suspend fun ensureModels(includeKorean: Boolean = true) = withContext(Dispatchers.IO) {
        try {
            copyIfNeeded(OcrModels.DET_FILE, OcrModels.DET_SIZE)
            copyIfNeeded(OcrModels.REC_V6_FILE, OcrModels.REC_V6_SIZE)
            if (includeKorean) copyIfNeeded(OcrModels.REC_KO_FILE, OcrModels.REC_KO_SIZE)
            refresh()
            val s = _state.value
            if (!s.primaryReady) {
                throw RuntimeException(
                    "Model tidak terbundel di APK ini. Build ulang via GitHub Action " +
                        "(workflow android.yml mengunduh ONNX ke assets/models/)."
                )
            }
        } catch (t: Throwable) {
            Log.e("ModelManager", "install failed", t)
            val cur = _state.value
            _state.value = cur.copy(installing = null, error = t.message ?: t.toString())
            throw t
        }
    }

    private suspend fun copyIfNeeded(fileName: String, expected: Long) =
        withContext(Dispatchers.IO) {
            val dst = File(modelsDir(), fileName)
            if (valid(dst, expected)) {
                emitProgress(null, 1f)
                return@withContext
            }
            val assetPath = "models/$fileName"
            try {
                appContext.assets.open(assetPath).use { ins ->
                    // available() tidak akurat untuk aset besar; salin streaming.
                    dst.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            // Progress kasar berbasis ukuran ekspektasi.
                            emitProgress(fileName, (done.toFloat() / expected).coerceIn(0f, 0.99f))
                        }
                    }
                }
            } catch (t: Throwable) {
                dst.delete()
                throw RuntimeException("assets/$assetPath hilang di APK. $t")
            }
            emitProgress(null, 1f)
        }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        detFile().delete(); recV6File().delete(); recKoFile().delete()
        refresh()
    }

    private fun emitProgress(name: String?, p: Float) {
        val cur = _state.value
        _state.value = cur.copy(
            detReady = valid(detFile(), OcrModels.DET_SIZE),
            recV6Ready = valid(recV6File(), OcrModels.REC_V6_SIZE),
            recKoReady = valid(recKoFile(), OcrModels.REC_KO_SIZE),
            installing = name,
            progress = p,
            error = null,
        )
    }
}
