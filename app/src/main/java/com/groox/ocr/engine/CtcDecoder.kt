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
        return RecResult(sb.toString(), score)
    }
}
