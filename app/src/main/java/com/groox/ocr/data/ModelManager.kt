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
 * CI mengunduh 5 file ONNX ke `app/src/main/assets/models/` SEBELUM build,
 * sehingga APK sudah berisi model — TIDAK ada unduhan runtime untuk OCR.
 * Saat pertama dibuka, model disalin dari assets (APK) ke
 * [Context.filesDir]/models agar bisa dibuka sebagai file oleh ONNX Runtime.
 *
 * Katalog:
 *  - det      : PP-OCRv6-small det (semua mode)
 *  - rec v6   : PP-OCRv6-small rec (auto 中文・日本語)
 *  - rec ko   : PP-OCRv5 korean (한국어)
 *  - rec en   : PP-OCRv5 en (English)
 *  - rec latin: PP-OCRv5 latin (ES/VI/ID dsb.)
 */
class ModelManager(private val appContext: Context) {

    data class ModelState(
        val detReady: Boolean = false,
        val recV6Ready: Boolean = false,
        val recKoReady: Boolean = false,
        val recEnReady: Boolean = false,
        val recLatinReady: Boolean = false,
        val installing: String? = null,
        val progress: Float = 0f,
        val error: String? = null,
    ) {
        val allReady: Boolean
            get() = detReady && recV6Ready && recKoReady && recEnReady && recLatinReady
        val primaryReady: Boolean get() = allReady
    }

    private val _state = MutableStateFlow(ModelState())
    val state: StateFlow<ModelState> = _state

    fun modelsDir(): File = File(appContext.filesDir, "models").apply { mkdirs() }
    fun detFile(): File = File(modelsDir(), OcrModels.DET_FILE)
    fun recV6File(): File = File(modelsDir(), OcrModels.REC_V6_FILE)
    fun recKoFile(): File = File(modelsDir(), OcrModels.REC_KO_FILE)
    fun recEnFile(): File = File(modelsDir(), OcrModels.REC_EN_FILE)
    fun recLatinFile(): File = File(modelsDir(), OcrModels.REC_LATIN_FILE)

    /** (fileName, expectedSize) untuk semua model yang wajib terbundel. */
    private fun allModels(): List<Pair<String, Long>> = listOf(
        OcrModels.DET_FILE to OcrModels.DET_SIZE,
        OcrModels.REC_V6_FILE to OcrModels.REC_V6_SIZE,
        OcrModels.REC_KO_FILE to OcrModels.REC_KO_SIZE,
        OcrModels.REC_EN_FILE to OcrModels.REC_EN_SIZE,
        OcrModels.REC_LATIN_FILE to OcrModels.REC_LATIN_SIZE,
    )

    fun refresh() {
        _state.value = snapshot()
    }

    private fun snapshot(
        installing: String? = null,
        progress: Float = 0f,
        error: String? = null,
    ): ModelState {
        fun v(name: String, expected: Long) = valid(File(modelsDir(), name), expected)
        return ModelState(
            detReady = v(OcrModels.DET_FILE, OcrModels.DET_SIZE),
            recV6Ready = v(OcrModels.REC_V6_FILE, OcrModels.REC_V6_SIZE),
            recKoReady = v(OcrModels.REC_KO_FILE, OcrModels.REC_KO_SIZE),
            recEnReady = v(OcrModels.REC_EN_FILE, OcrModels.REC_EN_SIZE),
            recLatinReady = v(OcrModels.REC_LATIN_FILE, OcrModels.REC_LATIN_SIZE),
            installing = installing,
            progress = progress,
            error = error,
        )
    }

    private fun valid(f: File, expected: Long): Boolean {
        if (!f.exists()) return false
        val len = f.length()
        return len > expected * 0.9 && len < expected * 1.3 && len > 1_000_000
    }

    /**
     * Salin model yang hilang dari assets/models/ ke filesDir/models/.
     * Dipanggil sekali saat startup / sebelum OCR; tanpa internet.
     */
    suspend fun ensureModels() = withContext(Dispatchers.IO) {
        try {
            for ((name, size) in allModels()) {
                copyIfNeeded(name, size)
            }
            refresh()
            val s = _state.value
            if (!s.allReady) {
                throw RuntimeException(
                    "Model tidak terbundel lengkap di APK ini. Build ulang via GitHub Action " +
                        "(workflow android.yml mengunduh 5 ONNX ke assets/models/)."
                )
            }
        } catch (t: Throwable) {
            Log.e("ModelManager", "install failed", t)
            _state.value = snapshot(error = t.message ?: t.toString())
            throw t
        }
    }

    private suspend fun copyIfNeeded(fileName: String, expected: Long) =
        withContext(Dispatchers.IO) {
            val dst = File(modelsDir(), fileName)
            if (valid(dst, expected)) {
                _state.value = snapshot(null, 1f)
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
                            _state.value = snapshot(
                                fileName,
                                (done.toFloat() / expected).coerceIn(0f, 0.99f),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                dst.delete()
                throw RuntimeException("assets/$assetPath hilang di APK. $t")
            }
            _state.value = snapshot(null, 1f)
        }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        for ((name, _) in allModels()) File(modelsDir(), name).delete()
        refresh()
    }
}
