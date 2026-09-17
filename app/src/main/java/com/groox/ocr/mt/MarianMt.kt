package com.groox.ocr.mt

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * MarianMT INT8 (Xenova opus-mt-*-int8) via ONNX Runtime.
 * encoder_model_int8.onnx + decoder_model_merged_int8.onnx, greedy decoding.
 * Nama input/output dibaca dinamis dari sesi (tahan variasi ekspor).
 */
class MarianMt(
    private val encoderFile: File,
    private val decoderFile: File,
    private val tokenizer: UnigramTokenizer,
    private val maxNewTokens: Int = 128,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    private fun sessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

    @Synchronized
    private fun enc(): OrtSession {
        encoder?.let { return it }
        require(encoderFile.exists()) { "Encoder hilang: ${encoderFile.name}" }
        return env.createSession(encoderFile.absolutePath, sessionOptions()).also { encoder = it }
    }

    @Synchronized
    private fun dec(): OrtSession {
        decoder?.let { return it }
        require(decoderFile.exists()) { "Decoder hilang: ${decoderFile.name}" }
        return env.createSession(decoderFile.absolutePath, sessionOptions()).also { decoder = it }
    }

    /** Terjemahkan satu teks pendek (satu baris/bubble). */
    fun translate(text: String): String {
        if (text.isBlank()) return text
        val ids = tokenizer.encode(text)
        if (ids.isEmpty()) return ""
        val encSession = enc()
        val decSession = dec()

        val n = ids.size
        val encInputs = linkedMapOf<String, OnnxTensor>()
        try {
            val names = encSession.inputNames
            if (names.contains("input_ids")) {
                encInputs["input_ids"] = longTensor(longArrayOf(1, n.toLong()), ids.map { it.toLong() })
            }
            if (names.contains("attention_mask")) {
                encInputs["attention_mask"] = longTensor(longArrayOf(1, n.toLong()), List(n) { 1L })
            }
            if (encInputs.isEmpty()) {
                // Fallback: beri input pertama apa pun namanya.
                encInputs[names.first()] =
                    longTensor(longArrayOf(1, n.toLong()), ids.map { it.toLong() })
            }
            encSession.run(encInputs).use { out ->
                val hidden = firstTensor(encSession, out)
                val encHidden = tensorToFloatArray(hidden)
                val encShape = hidden.info.shape // [1, n, hidden]
                val hDim = encShape.last().toInt()
                val decOut = greedyDecode(decSession, n, hDim, encHidden)
                return tokenizer.decode(decOut)
            }
        } finally {
            encInputs.values.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun greedyDecode(
        dec: OrtSession,
        encLen: Int,
        hDim: Int,
        encHidden: FloatArray,
    ): List<Int> {
        val decInputs = dec.inputNames.toList()
        val encHiddenName = decInputs.firstOrNull {
            it.contains("encoder_hidden", ignoreCase = true) || it.contains("encoder_out", ignoreCase = true)
        } ?: decInputs.firstOrNull { it.contains("encoder", ignoreCase = true) }
        val maskName = decInputs.firstOrNull {
            it.contains("encoder_attention_mask", ignoreCase = true)
        }
        val idName = decInputs.firstOrNull {
            it.equals("input_ids", ignoreCase = true)
        } ?: decInputs.firstOrNull { it.contains("input_id", ignoreCase = true) }
        requireNotNull(idName) { "Decoder tanpa input input_ids" }

        // Pasangan past (input) <-> present (output): cocokkan akhiran nama.
        val decOutputs = dec.outputNames.toList()
        val logitsName = decOutputs.firstOrNull {
            it.equals("logits", ignoreCase = true)
        } ?: decOutputs.first()
        val pastPairs = mutableListOf<Pair<String, String>>() // (inputPast, outputPresent)
        fun isReserved(n: String): Boolean = n == idName || n == encHiddenName || n == maskName
        // useCacheName ditemukan di bawah; kecualikan juga bila cocok pola past.
        for (o in decOutputs) {
            if (o == logitsName) continue
            val stem = o.replace("present", "###")
            val inp = decInputs.firstOrNull { cand ->
                !isReserved(cand) && !cand.contains("use_cache", ignoreCase = true) &&
                    (cand.replace("past_key_values", "###") == stem ||
                        cand.replace("past", "###") == stem)
            }
            if (inp != null) pastPairs.add(inp to o)
        }

        // Flag cabang cache ala Optimum (wajib di sebagian ekspor merged):
        // false = langkah pertama tanpa past, true = pakai past.
        val useCacheName = decInputs.firstOrNull {
            it.contains("use_cache", ignoreCase = true)
        }

        val result = mutableListOf<Int>()
        var token = tokenizer.padId // decoder_start_token_id = pad (Marian)
        var past: Map<String, FloatArray> = emptyMap()
        var pastShapes: Map<String, LongArray> = emptyMap()
        // Ekspor lawas tanpa use_cache: coba past nol dulu, fallback tanpa past.
        var triedZeroPast = useCacheName != null
        var skipPastInputs = false
        var step = 0
        // Mode full-recompute (tanpa cache): bila tak ada pasangan past sama
        // sekali, tiap langkah diberi SELURUH prefix + use_cache=false.
        val fullRecompute = useCacheName != null && pastPairs.isEmpty()
        while (step < maxNewTokens) {
            val feeds = linkedMapOf<String, OnnxTensor>()
            try {
                if (fullRecompute) {
                    val prefix = listOf(tokenizer.padId) + result
                    feeds[idName] = longTensor(
                        longArrayOf(1, prefix.size.toLong()),
                        prefix.map { it.toLong() },
                    )
                    if (useCacheName != null) feeds[useCacheName] = boolTensor(false)
                } else {
                    feeds[idName] = longTensor(longArrayOf(1, 1), listOf(token.toLong()))
                    if (useCacheName != null) feeds[useCacheName] = boolTensor(step > 0)
                }
                if (encHiddenName != null) {
                    feeds[encHiddenName] = floatTensor(
                        longArrayOf(1, encLen.toLong(), hDim.toLong()), encHidden
                    )
                }
                if (maskName != null) {
                    feeds[maskName] = longTensor(
                        longArrayOf(1, encLen.toLong()), List(encLen) { 1L }
                    )
                }
                if (!fullRecompute && !skipPastInputs && (step > 0 || useCacheName == null)) {
                    for ((inp, _) in pastPairs) {
                        val arr = past[inp]
                        val shp = pastShapes[inp]
                        if (arr != null && shp != null) {
                            feeds[inp] = floatTensor(shp, arr)
                        } else if (!triedZeroPast) {
                            // Marian-base: 8 heads × 64 dim.
                            feeds[inp] = floatTensor(longArrayOf(1, 8, 1, 64), FloatArray(8 * 64))
                        }
                    }
                }
                val stepOut: StepOut = try {
                    runDecoderStep(dec, feeds, logitsName, pastPairs)
                } catch (e: Exception) {
                    if (useCacheName == null && !triedZeroPast && past.isEmpty()) {
                        // Varian lawas yang menolak past di langkah pertama.
                        triedZeroPast = true
                        skipPastInputs = true
                        continue
                    }
                    throw e
                }
                triedZeroPast = true
                token = stepOut.token
                past = stepOut.past
                pastShapes = stepOut.shapes
                result.add(token)
                step++
            } finally {
                feeds.values.forEach { try { it.close() } catch (_: Exception) {} }
            }
            if (token == tokenizer.eosId) {
                result.removeLastOrNull()
                return result
            }
        }
        return result
    }

    private data class StepOut(
        val token: Int,
        val past: Map<String, FloatArray>,
        val shapes: Map<String, LongArray>,
    )

    /** Satu langkah decoder greedy (argmax posisi TERAKHIR — benar untuk
     *  cache 1-token maupun full-recompute multi-token). */
    private fun runDecoderStep(
        dec: OrtSession,
        feeds: Map<String, OnnxTensor>,
        logitsName: String,
        pastPairs: List<Pair<String, String>>,
    ): StepOut {
        dec.run(feeds).use { out ->
            val logitsT = out.get(logitsName).orElse(null) as? OnnxTensor
                ?: firstTensorFallback(out)
            val logits = tensorToFloatArray(logitsT)
            val shape = logitsT.info.shape // [..., seq, vocab]
            val vocab = shape.last().toInt()
            val seqLen = if (shape.size >= 2) shape[shape.size - 2].toInt() else 1
            val base = ((seqLen - 1).coerceAtLeast(0)) * vocab
            val token = argmax(logits, base, vocab)
            val nextPast = mutableMapOf<String, FloatArray>()
            val nextShapes = mutableMapOf<String, LongArray>()
            for ((inp, o) in pastPairs) {
                val t = out.get(o).orElse(null) as? OnnxTensor ?: continue
                nextPast[inp] = tensorToFloatArray(t)
                nextShapes[inp] = t.info.shape
            }
            return StepOut(token, nextPast, nextShapes)
        }
    }

    private fun argmax(a: FloatArray, base: Int = 0, len: Int = a.size - base): Int {
        var bi = 0
        var bv = a[base]
        for (i in 1 until len) {
            if (base + i >= a.size) break
            if (a[base + i] > bv) {
                bv = a[base + i]
                bi = i
            }
        }
        return bi
    }

    private fun firstTensor(session: OrtSession, out: OrtSession.Result): OnnxTensor {
        for (name in session.outputNames) {
            try {
                val v = out.get(name).orElse(null)
                if (v is OnnxTensor) return v
            } catch (_: Exception) {}
        }
        return firstTensorFallback(out)
    }

    private fun firstTensorFallback(out: OrtSession.Result): OnnxTensor {
        for (e in out) {
            if (e.value is OnnxTensor) return e.value as OnnxTensor
        }
        throw RuntimeException("Model tidak mengembalikan tensor")
    }

    private fun boolTensor(value: Boolean): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        buf.put(if (value) 1 else 0)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1), OnnxJavaType.BOOL)
    }

    private fun longTensor(shape: LongArray, data: List<Long>): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        data.forEach { buf.put(it) }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf as LongBuffer, shape)
    }

    private fun floatTensor(shape: LongArray, data: FloatArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf as FloatBuffer, shape)
    }

    private fun tensorToFloatArray(t: OnnxTensor): FloatArray {
        val buf = t.floatBuffer
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    override fun close() {
        try { encoder?.close() } catch (_: Exception) {}
        try { decoder?.close() } catch (_: Exception) {}
        encoder = null
        decoder = null
    }
}
