package com.groox.ocr.mt

import android.content.Context
import java.text.Normalizer
import org.json.JSONObject

/**
 * Tokenizer Unigram (SentencePiece) untuk MarianMT, dibaca dari
 * `tokenizer.json` Xenova (tanpa dependensi tambahan; org.json bawaan Android).
 *
 * Steps (sesuai pretokenizer HF: WhitespaceSplit + Metaspace add_prefix_space):
 * NFKC → "▁" + teks.trim().replace(whitespace+, "▁") → Viterbi best-path.
 */
class UnigramTokenizer private constructor(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val idOf: Map<String, Int>,
    val unkId: Int,
    val eosId: Int,
    val padId: Int,
) {
    private val trie = Trie()
    private val minScore: Float

    init {
        var m = Float.MAX_VALUE
        pieces.forEachIndexed { i, p ->
            trie.insert(p, i)
            if (i >= 3 && scores[i] < m) m = scores[i]
        }
        minScore = if (m == Float.MAX_VALUE) -10f else m
    }

    /** Encode → id list + EOS di akhir (konvensi sumber Marian). */
    fun encode(text: String): IntArray {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        s = "▁" + s.trim().replace(Regex("\\s+"), "▁")
        if (s == "▁" || s.isEmpty()) return intArrayOf(eosId)
        val n = s.length
        val unkScore = (minScore - 10f).toDouble()
        val ids = mutableListOf<Int>()
        // Viterbi best-path dengan fallback unk per kodepoin.
        val b2 = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val bi = IntArray(n + 1) { -1 }
        val bf = IntArray(n + 1) { -1 }
        b2[0] = 0.0
        var p = 0
        while (p < n) {
            if (b2[p] != Double.NEGATIVE_INFINITY) {
                var any = false
                for (id in trie.match(s, p)) {
                    val len = pieces[id].length
                    val cand = b2[p] + scores[id]
                    if (cand > b2[p + len]) {
                        b2[p + len] = cand
                        bi[p + len] = id
                        bf[p + len] = p
                    }
                    any = true
                }
                if (!any) {
                    val cpLen = Character.charCount(s.codePointAt(p))
                    val cand = b2[p] + unkScore
                    if (cand > b2[p + cpLen]) {
                        b2[p + cpLen] = cand
                        bi[p + cpLen] = unkId
                        bf[p + cpLen] = p
                    }
                }
            }
            p++
        }
        var k = n
        if (b2[n] == Double.NEGATIVE_INFINITY) return intArrayOf(eosId)
        while (k > 0) {
            ids.add(bi[k])
            k = bf[k]
        }
        ids.reverse()
        ids.add(eosId)
        return ids.toIntArray()
    }

    /** Decode: gabung piece, ▁ → spasi. Token spesial dilewati. */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == padId || id == eosId) continue
            if (id == unkId) {
                sb.append("⁇")
                continue
            }
            if (id in pieces.indices) sb.append(pieces[id])
        }
        var s = sb.toString().replace("▁", " ")
        // Hilangkan spasi sebelum tanda baca ala detokenizer dasar.
        s = s.replace(Regex("\\s+([.,!?;:%)\\]}])"), "$1")
            .replace(Regex("([(\\[{])\\s+"), "$1")
        return s.trim()
    }

    companion object {
        fun load(assetText: String, padId: Int, eosId: Int = 0, unkId: Int = 1): UnigramTokenizer {
            val root = JSONObject(assetText)
            val model = root.getJSONObject("model")
            val vocab = model.getJSONArray("vocab")
            val pieces = ArrayList<String>(vocab.length())
            val scores = FloatArray(vocab.length())
            for (i in 0 until vocab.length()) {
                val e = vocab.getJSONArray(i)
                pieces.add(e.getString(0))
                scores[i] = e.getDouble(1).toFloat()
            }
            val idOf = HashMap<String, Int>(pieces.size * 2)
            pieces.forEachIndexed { i, p -> idOf.putIfAbsent(p, i) }
            return UnigramTokenizer(pieces, scores, idOf, unkId, eosId, padId)
        }

        fun loadAsset(ctx: Context, assetPath: String, padId: Int): UnigramTokenizer {
            val text = ctx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return load(text, padId)
        }
    }

    /** Trie sederhana untuk longest-match kandidat piece. */
    private class Trie {
        private class Node {
            val next = HashMap<Char, Node>()
            var id: Int = -1
        }

        private val root = Node()

        fun insert(piece: String, id: Int) {
            var n = root
            for (c in piece) n = n.next.getOrPut(c) { Node() }
            if (n.id < 0) n.id = id
        }

        fun match(s: String, from: Int): List<Int> {
            val out = ArrayList<Int>(4)
            var n = root
            var i = from
            while (i < s.length) {
                n = n.next[s[i]] ?: break
                if (n.id >= 0) out.add(n.id)
                i++
            }
            return out
        }
    }
}
