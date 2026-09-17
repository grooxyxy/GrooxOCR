package com.groox.ocr.mt

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Registry 2 pasang MarianMT-INT8 + chaining KO→ID (KO→EN lalu EN→ID).
 * File model disalin dari assets/mt/<pair>/ (diunduh CI) ke filesDir/mt/.
 */
class Translator(private val appContext: Context) {

    enum class Direction(val label: String) {
        KO_EN("Korea → Inggris"),
        EN_ID("Inggris → Indonesia"),
        KO_ID("Korea → Indonesia (via Inggris)"),
    }

    private val lock = Any()
    private var koEn: MarianMt? = null
    private var enId: MarianMt? = null
    private var tokKoEn: UnigramTokenizer? = null
    private var tokEnId: UnigramTokenizer? = null

    fun modelsDir(): File = File(appContext.filesDir, "mt").apply { mkdirs() }

    fun pairDir(pair: String): File = File(modelsDir(), pair).apply { mkdirs() }

    /** Status kesiapan file per pasangan (dipakai UI). */
    fun ready(pair: String): Boolean {
        val d = pairDir(pair)
        return File(d, "encoder_int8.onnx").length() > 1_000_000 &&
            File(d, "decoder_merged_int8.onnx").length() > 1_000_000 &&
            File(d, "tokenizer.json").length() > 100_000
    }

    /** Salin dari assets bila belum ada (tanpa internet). */
    fun ensureAssets(pair: String) {
        val d = pairDir(pair)
        copyAsset("mt/$pair/encoder_int8.onnx", File(d, "encoder_int8.onnx"))
        copyAsset("mt/$pair/decoder_merged_int8.onnx", File(d, "decoder_merged_int8.onnx"))
        copyAsset("mt/$pair/tokenizer.json", File(d, "tokenizer.json"))
        if (!ready(pair)) {
            throw RuntimeException(
                "Model $pair belum terbundel. Build ulang via GitHub Action."
            )
        }
    }

    private fun copyAsset(assetPath: String, dst: File) {
        if (dst.exists() && dst.length() > 10_000) return
        try {
            appContext.assets.open(assetPath).use { ins ->
                dst.outputStream().use { ins.copyTo(it) }
            }
        } catch (t: Throwable) {
            dst.delete()
            throw RuntimeException("assets/$assetPath hilang di APK. $t")
        }
    }

    private fun tokenizer(pair: String, padId: Int): UnigramTokenizer {
        val d = pairDir(pair)
        val text = File(d, "tokenizer.json").readText(Charsets.UTF_8)
        return UnigramTokenizer.load(text, padId)
    }

    @Synchronized
    private fun mtKoEn(): MarianMt {
        koEn?.let { return it }
        ensureAssets("ko-en")
        val d = pairDir("ko-en")
        tokKoEn = tokenizer("ko-en", 65000)
        return MarianMt(
            File(d, "encoder_int8.onnx"),
            File(d, "decoder_merged_int8.onnx"),
            tokKoEn!!,
        ).also { koEn = it }
    }

    @Synchronized
    private fun mtEnId(): MarianMt {
        enId?.let { return it }
        ensureAssets("en-id")
        val d = pairDir("en-id")
        tokEnId = tokenizer("en-id", 54795)
        return MarianMt(
            File(d, "encoder_int8.onnx"),
            File(d, "decoder_merged_int8.onnx"),
            tokEnId!!,
        ).also { enId = it }
    }

    /** Terjemahkan teks multi-baris; baris kosong dipertahankan. */
    fun translate(
        text: String,
        dir: Direction,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String {
        val lines = text.replace("\r", "").split("\n")
        val total = lines.size.coerceAtLeast(1)
        var firstError: Throwable? = null
        val out = lines.mapIndexed { i, ln ->
            onProgress(i, total)
            if (ln.isBlank()) "" else try {
                translateLine(ln, dir)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                Log.w("Translator", "gagal: $t")
                ln // fallback per baris agar hasil tak hilang total
            }
        }
        onProgress(total, total)
        // Jika TAK ADA satu baris pun yang berubah, hampir pasti model gagal
        // total (bukan "tidak perlu diterjemahkan") → tampilkan penyebab asli
        // alih-alih diam-diam mengembalikan teks Korea.
        val joined = out.joinToString("\n")
        if (firstError != null && joined == text.replace("\r", "")) {
            throw RuntimeException("Translate gagal: ${firstError!!.message}", firstError)
        }
        return joined
    }

    private fun translateLine(ln: String, dir: Direction): String {
        // Pecah baris super-panjang per ±60 kata agar muat konteks 512.
        val words = ln.trim().split(Regex("\\s+"))
        if (words.size <= 60) {
            return one(ln, dir)
        } else {
            return words.chunked(60).joinToString(" ") { one(it.joinToString(" "), dir) }
        }
    }

    private fun one(s: String, dir: Direction): String {
        return when (dir) {
            Direction.KO_EN -> mtKoEn().translate(s)
            Direction.EN_ID -> mtEnId().translate(s)
            Direction.KO_ID -> mtEnId().translate(mtKoEn().translate(s))
        }
    }

    fun close() {
        synchronized(lock) {
            try { koEn?.close() } catch (_: Exception) {}
            try { enId?.close() } catch (_: Exception) {}
            koEn = null
            enId = null
        }
    }
}
