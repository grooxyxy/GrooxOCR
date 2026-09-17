package com.groox.ocr.engine

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * DBNet postprocess in pure Kotlin (no OpenCV) for axis-aligned comic text.
 *
 * Pipeline: probMap (model output, stride 4) → binarize [thresh] →
 * connected components (BFS) → per-component bbox + mean score filter
 * [boxThresh] → unclip expansion [unclipRatio] → clip → size filter → NMS.
 *
 * Boxes are returned in ORIGINAL tile-bitmap coordinates.
 */
object DetPostprocess {

    data class DetBox(val rect: RectF, val score: Float)

    fun decode(
        prob: FloatArray,
        outShape: LongArray, // e.g. [1,1,h/4,w/4] or [1,h/4,w/4]
        resizedW: Int,
        resizedH: Int,
        origW: Int,
        origH: Int,
        thresh: Float = 0.2f,
        boxThresh: Float = 0.5f,
        unclipRatio: Float = 1.4f,
        maxCandidates: Int = 3000,
    ): List<DetBox> {
        val (mw, mh) = outDims(outShape)
        require(prob.size >= mw * mh) { "prob size ${prob.size} < $mw*$mh" }

        // 1) binarize
        val mask = BooleanArray(mw * mh) { prob[it] > thresh }

        // 2) connected components via BFS queue (4-connectivity)
        val label = IntArray(mw * mh) { -1 }
        val boxes = mutableListOf<DetBox>()
        var compId = 0
        val queue = ArrayDeque<Int>(1024)
        for (s in mask.indices) {
            if (!mask[s] || label[s] != -1) continue
            if (compId >= maxCandidates) break
            queue.clear()
            queue.add(s)
            label[s] = compId
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            var sum = 0.0
            var cnt = 0
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val cx = cur % mw
                val cy = cur / mw
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                sum += prob[cur]
                cnt++
                // 4-neighbours
                if (cx > 0) {
                    val n = cur - 1
                    if (mask[n] && label[n] == -1) { label[n] = compId; queue.add(n) }
                }
                if (cx + 1 < mw) {
                    val n = cur + 1
                    if (mask[n] && label[n] == -1) { label[n] = compId; queue.add(n) }
                }
                if (cy > 0) {
                    val n = cur - mw
                    if (mask[n] && label[n] == -1) { label[n] = compId; queue.add(n) }
                }
                if (cy + 1 < mh) {
                    val n = cur + mw
                    if (mask[n] && label[n] == -1) { label[n] = compId; queue.add(n) }
                }
            }
            compId++
            if (cnt < 9) continue // noise
            val score = (sum / cnt).toFloat()
            if (score < boxThresh) continue

            // Map prob-map coords → resized image coords.
            // DBNet output stride is typically 4; derive scale from shapes.
            val sx = resizedW.toFloat() / mw
            val sy = resizedH.toFloat() / mh
            var x0 = minX * sx
            var y0 = minY * sy
            var x1 = (maxX + 1) * sx
            var y1 = (maxY + 1) * sy

            // 3) unclip expansion: offset = area*ratio / perimeter (rect approx).
            val w = (x1 - x0).coerceAtLeast(1f)
            val h = (y1 - y0).coerceAtLeast(1f)
            val offset = (w * h * unclipRatio) / (2f * (w + h))
            x0 -= offset; y0 -= offset; x1 += offset; y1 += offset

            // 4) to original coords (undo det resize scale).
            val invSx = origW.toFloat() / resizedW
            val invSy = origH.toFloat() / resizedH
            x0 *= invSx; x1 *= invSx; y0 *= invSy; y1 *= invSy

            x0 = x0.coerceIn(0f, origW.toFloat())
            y0 = y0.coerceIn(0f, origH.toFloat())
            x1 = x1.coerceIn(0f, origW.toFloat())
            y1 = y1.coerceIn(0f, origH.toFloat())
            val fw = x1 - x0
            val fh = y1 - y0
            if (fw < 8 || fh < 8) continue
            if (fw * fh < 64) continue
            boxes.add(DetBox(RectF(x0, y0, x1, y1), score))
        }
        return nms(boxes, 0.3f)
    }

    private fun outDims(shape: LongArray): Pair<Int, Int> {
        return when (shape.size) {
            4 -> Pair(shape[3].toInt(), shape[2].toInt()) // NCHW
            3 -> Pair(shape[2].toInt(), shape[1].toInt())
            else -> throw IllegalArgumentException("Unexpected det output shape: ${shape.toList()}")
        }
    }

    /** Axis-aligned NMS, keeps higher score. */
    fun nms(boxes: List<DetBox>, iouThresh: Float): List<DetBox> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<DetBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(best.rect, it.next().rect) > iouThresh) it.remove()
            }
        }
        return keep
    }

    fun iou(a: RectF, b: RectF): Float {
        val ix0 = max(a.left, b.left)
        val iy0 = max(a.top, b.top)
        val ix1 = min(a.right, b.right)
        val iy1 = min(a.bottom, b.bottom)
        val iw = (ix1 - ix0).coerceAtLeast(0f)
        val ih = (iy1 - iy0).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
