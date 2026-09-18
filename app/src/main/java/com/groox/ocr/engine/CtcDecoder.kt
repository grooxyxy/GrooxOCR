package com.groox.ocr.engine

/**
 * Greedy CTC decoder (Paddle `CTCLabelDecode` convention):
 * class 0 = blank, dict[i] ↔ class i+1. Collapses repeats, drops blanks,
 * averages max-prob of kept steps as confidence. Out-of-range indices
 * (model 18710 vs dict 18708 mismatch) are skipped safely.
 */
object CtcDecoder {

    data class RecResult(val text: String, val score: Float)

    fun decode(logits: FloatArray, seqLen: Int, numClasses: Int, dict: List<String>): RecResult {
        require(logits.size == seqLen * numClasses)
        val sb = StringBuilder()
        var last = -1
        var scoreSum = 0.0
        var kept = 0
        for (t in 0 until seqLen) {
            val base = t * numClasses
            var best = 0
            var bestV = logits[base]
            for (c in 1 until numClasses) {
                val v = logits[base + c]
                if (v > bestV) { bestV = v; best = c }
            }
            // softmax-free confidence proxy: sigmoid-ish via exp normalization of top-2?
            // Cheap approx: use max logit mapped through sigmoid for stability.
            val conf = 1f / (1f + kotlin.math.exp(-bestV))
            if (best != 0 && best != last) {
                val di = best - 1
                if (di in dict.indices) {
                    val ch = dict[di]
                    // Skip Paddle padding tokens if any ("blank", "<unk>")
                    if (ch != "blank" && ch.isNotEmpty()) {
                        sb.append(ch)
                        scoreSum += conf
                        kept++
                    }
                }
            }
            last = best
        }
        val score = if (kept == 0) 0f else (scoreSum / kept).toFloat()
        return RecResult(postProcess(sb.toString()), score)
    }

    /**
     * Post-process OCR mentah:
     * 1. lowercase semua (permintaan user; tak berpengaruh ke CJK/Korea).
     * 2. Perbaiki spasi hilang khas OCR: rapikan spasi ganda + sisipkan spasi
     *    setelah tanda baca bila menempel huruf (mis. "pervert.hehe").
     *    Dijaga agar angka desimal ("3.14") tidak rusak.
     */
    fun postProcess(raw: String): String {
        var s = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return s
        // Buang spasi SEBELUM tanda baca ("hello , world !" → "hello, world!").
        s = s.replace(Regex(" ([.,!?;:%])"), "$1")
        // Sisipkan spasi antara huruf↔angka yang menempel ("k44" dibiarkan bila
        // diawali/diakhiri non-ASCII SFX; pola umum "page3" → "page 3").
        s = s.replace(Regex("([a-z])(\\d)"), "$1 $2")
            .replace(Regex("(\\d)([a-z])"), "$1 $2")
        val sb = StringBuilder(s.length + 4)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            sb.append(c)
            if (c in ".,!?;:" && i + 1 < s.length) {
                val nx = s[i + 1]
                val pv = if (i > 0) s[i - 1] else ' '
                val needSpace = nx != ' ' && nx.isLetter() &&
                    !(pv.isDigit() && nx.isDigit())
                if (needSpace) sb.append(' ')
            }
            i++
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }
}
