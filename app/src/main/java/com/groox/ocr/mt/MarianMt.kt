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
 * MarianMT INT8 (Xenova opus-mt-*-int8) via ONNX Runtime, greedy decoding.
 *
 * Jalur utama: decoder TERPISAH (tanpa node If sama sekali) —
 * `decoder_int8` untuk langkah pertama, `decoder_with_past_int8` sesudahnya.
 * Fallback: `decoder_merged_int8` + flag `use_cache_branch` bila file
 * terpisah tak ada. Nama input/output dibaca dinamis dari sesi.
 */
class MarianMt(
    private val encoderFile: File,
    private val split: SplitFiles?,
    private val mergedFile: File?,
    private val tokenizer: UnigramTokenizer,
    private val maxNewTokens: Int = 128,
) : AutoCloseable {

    data class SplitFiles(val noPast: File, val withPast: File)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var dec0: OrtSession? = null
    private var decPast: OrtSession? = null
    private var merged: OrtSession? = null

    private fun sessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

    private fun open(f: File, tag: String): OrtSession {
        require(f.exists() && f.length() > 1_000_000) { "$tag hilang: ${f.name}" }
        return env.createSession(f.absolutePath, sessionOptions())
    }

    @Synchronized
    private fun enc(): OrtSession {
        encoder?.let { return it }
        return open(encoderFile, "Encoder").also { encoder = it }
    }

    @Synchronized
    private fun sess0(): OrtSession? {
        dec0?.let { return it }
        val f = split?.noPast ?: return null
        if (!f.exists() || f.length() <= 1_000_000) return null
        return open(f, "Decoder").also { dec0 = it }
    }

    @Synchronized
    private fun sessPast(): OrtSession? {
        decPast?.let { return it }
        val f = split?.withPast ?: return null
        if (!f.exists() || f.length() <= 1_000_000) return null
        return open(f, "Decoder-past").also { decPast = it }
    }

    @Synchronized
    private fun sessMerged(): OrtSession? {
        merged?.let { return it }
        val f = mergedFile ?: return null
        if (!f.exists() || f.length() <= 1_000_000) return null
        return open(f, "Decoder-merged").also { merged = it }
    }

    /** Terjemahkan satu teks pendek (satu baris/bubble). */
    fun translate(text: String): String {
        if (text.isBlank()) return text
        val ids = tokenizer.encode(text)
        if (ids.isEmpty()) return ""
        val encSession = enc()

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
                encInputs[names.first()] =
                    longTensor(longArrayOf(1, n.toLong()), ids.map { it.toLong() })
            }
            encSession.run(encInputs).use { out ->
                val hidden = firstTensor(encSession, out)
                val encHidden = tensorToFloatArray(hidden)
                val hDim = hidden.info.shape.last().toInt()
                return tokenizer.decode(greedyDecode(n, hDim, encHidden))
            }
        } finally {
            encInputs.values.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    /** Info nama IO sesi — disertakan di pesan error agar debug cepat. */
    private fun ioInfo(s: OrtSession): String =
        "in=${s.inputNames} out=${s.outputNames}"

    private data class EP(
        val id: String?,
        val encHidden: String?,
        val mask: String?,
        val useCache: String?,
        val logits: String,
        val pairs: List<Pair<String, String>>,
    )

    private fun endpoints(s: OrtSession): EP {
        val ins = s.inputNames.toList()
        val id = ins.firstOrNull { it.equals("input_ids", ignoreCase = true) }
            ?: ins.firstOrNull { it.contains("input_id", ignoreCase = true) }
        requireNotNull(id) { "Tanpa input_ids. ${ioInfo(s)}" }
        val encH = ins.firstOrNull {
            it.contains("encoder_hidden", ignoreCase = true) || it.contains("encoder_out", ignoreCase = true)
        } ?: ins.firstOrNull { it.contains("encoder", ignoreCase = true) && !it.contains("mask", ignoreCase = true) }
        val mask = ins.firstOrNull { it.contains("encoder_attention_mask", ignoreCase = true) }
        val useCache = ins.firstOrNull { it.contains("use_cache", ignoreCase = true) }
        val outs = s.outputNames.toList()
        val logits = outs.firstOrNull { it.equals("logits", ignoreCase = true) } ?: outs.first()
        val pairs = mutableListOf<Pair<String, String>>()
        for (o in outs) {
            if (o == logits) continue
            val stem = o.replace("present", "###")
            val inp = ins.firstOrNull { c ->
                c != id && c != encH && c != mask && c != useCache &&
                    !c.contains("use_cache", ignoreCase = true) &&
                    (c.replace("past_key_values", "###") == stem ||
                        c.replace("past", "###") == stem)
            }
            if (inp != null) pairs.add(inp to o)
        }
        return EP(id, encH, mask, useCache, logits, pairs)
    }

    private fun greedyDecode(encLen: Int, hDim: Int, encHidden: FloatArray): List<Int> {
        val useSplit = sess0() != null && sessPast() != null
        val mergedSess = if (useSplit) null else sessMerged()
            ?: throw RuntimeException("Tidak ada file decoder (split maupun merged)")
        val ep0 = endpoints(if (useSplit) sess0()!! else mergedSess!!)
        val epPast = if (useSplit) endpoints(sessPast()!!) else ep0

        val result = mutableListOf<Int>()
        var token = tokenizer.padId // decoder_start_token_id = pad (Marian)
        var past: Map<String, FloatArray> = emptyMap()
        var pastShapes: Map<String, LongArray> = emptyMap()
        var step = 0
        while (step < maxNewTokens) {
            val sess = if (useSplit && step > 0) sessPast()!! else
                (if (useSplit) sess0()!! else mergedSess!!)
            val ep = if (useSplit && step > 0) epPast else ep0
            val feeds = linkedMapOf<String, OnnxTensor>()
            try {
                val isMerged = !useSplit
                if (isMerged && ep.pairs.isEmpty() && ep.useCache != null) {
                    // Full-recompute: seluruh prefix + use_cache=false.
                    val prefix = listOf(tokenizer.padId) + result
                    feeds[ep.id!!] = longTensor(
                        longArrayOf(1, prefix.size.toLong()), prefix.map { it.toLong() }
                    )
                    feeds[ep.useCache] = boolTensor(false)
                } else {
                    feeds[ep.id!!] = longTensor(longArrayOf(1, 1), listOf(token.toLong()))
                    if (ep.useCache != null) feeds[ep.useCache] = boolTensor(step > 0)
                }
                if (ep.encHidden != null) {
                    feeds[ep.encHidden] = floatTensor(
                        longArrayOf(1, encLen.toLong(), hDim.toLong()), encHidden
                    )
                }
                if (ep.mask != null) {
                    feeds[ep.mask] = longTensor(
                        longArrayOf(1, encLen.toLong()), List(encLen) { 1L }
                    )
                }
                if (step > 0 || (isMerged && ep.useCache == null)) {
                    for ((inp, _) in ep.pairs) {
                        val arr = past[inp]
                        val shp = pastShapes[inp]
                        if (arr != null && shp != null) feeds[inp] = floatTensor(shp, arr)
                    }
                }
                val stepOut: StepOut = try {
                    runDecoderStep(sess, feeds, ep)
                } catch (e: Exception) {
                    throw RuntimeException("${ioInfo(sess)} ← ${e.message}", e)
                }
                token = stepOut.token
                // Samakan kunci past ke nama input sesi langkah berikut.
                val epNext = if (useSplit) epPast else ep0
                past = remapPast(stepOut.past, stepOut.shapes, ep.pairs, epNext.pairs)
                pastShapes = remapShapes(stepOut.shapes, ep.pairs, epNext.pairs)
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

    /** Petakan past {inputName→data} sesi A ke nama input sesi B via akhiran. */
    private fun remapPast(
        past: Map<String, FloatArray>,
        shapes: Map<String, LongArray>,
        fromPairs: List<Pair<String, String>>,
        toPairs: List<Pair<String, String>>,
    ): Map<String, FloatArray> {
        if (toPairs.isEmpty()) return past
        // Kunci berdasar NAMA OUTPUT present (stabil antar sesi bila sama).
        val byPresent = mutableMapOf<String, FloatArray>()
        val shpPresent = mutableMapOf<String, LongArray>()
        for ((inp, o) in fromPairs) {
            if (past.containsKey(inp)) {
                byPresent[o] = past[inp]!!
                if (shapes.containsKey(inp)) shpPresent[o] = shapes[inp]!!
            }
        }
        val out = mutableMapOf<String, FloatArray>()
        for ((inp, o) in toPairs) {
            if (byPresent.containsKey(o)) out[inp] = byPresent[o]!!
        }
        // Bila tak ada yang cocok (nama beda total), teruskan apa adanya.
        return if (out.isEmpty()) past else out
    }

    private fun remapShapes(
        shapes: Map<String, LongArray>,
        fromPairs: List<Pair<String, String>>,
        toPairs: List<Pair<String, String>>,
    ): Map<String, LongArray> {
        if (toPairs.isEmpty()) return shapes
        val byPresent = mutableMapOf<String, LongArray>()
        for ((inp, o) in fromPairs) {
            if (shapes.containsKey(inp)) byPresent[o] = shapes[inp]!!
        }
        val out = mutableMapOf<String, LongArray>()
        for ((inp, o) in toPairs) {
            if (byPresent.containsKey(o)) out[inp] = byPresent[o]!!
        }
        return if (out.isEmpty()) shapes else out
    }

    private data class StepOut(
        val token: Int,
        val past: Map<String, FloatArray>,
        val shapes: Map<String, LongArray>,
    )

    /** Satu langkah decoder greedy (argmax posisi TERAKHIR). */
    private fun runDecoderStep(
        dec: OrtSession,
        feeds: Map<String, OnnxTensor>,
        ep: EP,
    ): StepOut {
        dec.run(feeds).use { out ->
            val logitsT = out.get(ep.logits).orElse(null) as? OnnxTensor
                ?: firstTensorFallback(out)
            val logits = tensorToFloatArray(logitsT)
            val shape = logitsT.info.shape // [..., seq, vocab]
            val vocab = shape.last().toInt()
            val seqLen = if (shape.size >= 2) shape[shape.size - 2].toInt() else 1
            val base = ((seqLen - 1).coerceAtLeast(0)) * vocab
            val token = argmax(logits, base, vocab)
            val nextPast = mutableMapOf<String, FloatArray>()
            val nextShapes = mutableMapOf<String, LongArray>()
            for ((inp, o) in ep.pairs) {
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
        try { dec0?.close() } catch (_: Exception) {}
        try { decPast?.close() } catch (_: Exception) {}
        try { merged?.close() } catch (_: Exception) {}
        encoder = null
        dec0 = null
        decPast = null
        merged = null
    }
}
