package com.groox.ocr.engine

import android.graphics.RectF
import com.groox.ocr.data.ReadingOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Groups per-line Det+Rec results into per-BUBBLE paragraphs.
 *
 * Manhwa/manga bubbles contain 1..N stacked lines with small gaps.
 * Algorithm:
 * 1) Expand each line box by (padPx + padRatio*h) in all directions.
 * 2) Union-find merge on expanded-box overlap (or gap <= mergeGap).
 * 3) Sort bubbles in reading order; sort lines inside bubble top-to-bottom.
 * 4) Join line texts with "\n" (bubble text) — UI can also show " " joined.
 */
object BubbleGrouper {

    data class Line(
        val rect: RectF, // original image coords
        val text: String,
        val score: Float,
    )

    data class Bubble(
        val id: Int, // 0-based in reading order
        val rect: RectF, // union of member lines
        val lines: List<Line>, // sorted top-to-bottom
        val text: String, // lines joined by \n
        val avgScore: Float,
    )

    fun group(
        lines: List<Line>,
        padPx: Int = 12,
        padRatio: Float = 0.08f,
        mergeGap: Int = 40,
        order: ReadingOrder = ReadingOrder.TOP_TO_BOTTOM_LTR,
        imageWidth: Int = 0,
    ): List<Bubble> {
        if (lines.isEmpty()) return emptyList()
        val n = lines.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val expanded = lines.map { l ->
            val pad = padPx + l.rect.height() * padRatio
            RectF(
                l.rect.left - pad,
                l.rect.top - pad,
                l.rect.right + pad,
                l.rect.bottom + pad,
            )
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (shouldMerge(expanded[i], expanded[j], lines[i].rect, lines[j].rect, mergeGap)) {
                    union(i, j)
                }
            }
        }

        val clusters = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            clusters.getOrPut(find(i)) { mutableListOf() }.add(i)
        }

        // Sort lines inside bubble top-to-bottom (then x by order).
        val bubbles = clusters.values.map { idxs ->
            val members = idxs.map { lines[it] }.sortedWith(
                compareBy({ it.rect.centerY() }, { it.rect.left })
            )
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = Float.MIN_VALUE; var b = Float.MIN_VALUE
            var s = 0f
            for (m in members) {
                l = min(l, m.rect.left); t = min(t, m.rect.top)
                r = max(r, m.rect.right); b = max(b, m.rect.bottom)
                s += m.score
            }
            Bubble(-1, RectF(l, t, r, b), members, members.joinToString("\n") { it.text }, s / members.size)
        }.toMutableList()

        // Sort bubbles in reading order: primary top, secondary x.
        val sorted = when (order) {
            ReadingOrder.TOP_TO_BOTTOM_LTR ->
                bubbles.sortedWith(compareBy({ rowKey(it.rect.top) }, { it.rect.left }))
            ReadingOrder.TOP_TO_BOTTOM_RTL ->
                bubbles.sortedWith(compareBy({ rowKey(it.rect.top) }, { -(it.rect.left) }))
        }
        return sorted.mapIndexed { idx, bb -> bb.copy(id = idx) }
    }

    /**
     * Two lines merge if expanded boxes intersect, OR if vertical gap is small
     * and horizontal ranges overlap substantially (stacked bubble lines).
     */
    private fun shouldMerge(
        e1: RectF, e2: RectF,
        o1: RectF, o2: RectF,
        mergeGap: Int,
    ): Boolean {
        if (RectF.intersects(e1, e2)) return true
        // Vertical stacking check on ORIGINAL boxes with tolerance.
        val vGap = if (o1.bottom < o2.top) o2.top - o1.bottom
        else if (o2.bottom < o1.top) o1.top - o2.bottom
        else 0f
        if (vGap > mergeGap) return false
        val hOverlap = min(o1.right, o2.right) - max(o1.left, o2.left)
        val minW = min(o1.width(), o2.width())
        if (minW <= 0) return false
        // Same bubble column: horizontal overlap > 30% of narrower line.
        if (hOverlap / minW > 0.3f) return true
        // Narrow SFX / single-char lines: allow center-x proximity.
        val cx1 = o1.centerX(); val cx2 = o2.centerX()
        val avgW = (o1.width() + o2.width()) / 2f
        return kotlin.math.abs(cx1 - cx2) < avgW * 0.8f && vGap < mergeGap * 2
    }

    /** Quantize top so near-same-row bubbles order deterministically. */
    private fun rowKey(top: Float): Int = (top / 24f).toInt()

    private fun RectF.centerY(): Float = (top + bottom) / 2f
}
