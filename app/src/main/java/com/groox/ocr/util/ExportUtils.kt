package com.groox.ocr.util

import android.content.Context
import android.net.Uri
import com.groox.ocr.engine.BubbleGrouper
import com.groox.ocr.engine.OcrEngine
import java.io.File

/** Export bubble results as TXT (per-bubble) and JSON. */
object ExportUtils {

    /** Awalan baris yang bisa dipilih user, mis. "- halo". */
    enum class BubblePrefix(val label: String) {
        NONE("Tanpa awalan"),
        DASH("Strip  (- teks)"),
        BULLET("Bullet  (• teks)"),
        QUOTE("Kutip  (> teks)"),
        NUMBERED("Nomor  (1. teks)"),
        ;
        fun apply(text: String, number: Int): String = when (this) {
            NONE -> text
            DASH -> "- $text"
            BULLET -> "• $text"
            QUOTE -> "> $text"
            NUMBERED -> "$number. $text"
        }
    }

    /** Satu bubble = satu baris (bubble.text sendiri sudah satu baris). */
    fun bubblesToTxt(
        bubbles: List<BubbleGrouper.Bubble>,
        prefix: BubblePrefix = BubblePrefix.NONE,
        startNumber: Int = 1,
    ): String = bubbles.mapIndexed { i, b ->
        prefix.apply(b.text, startNumber + i)
    }.joinToString("\n")

    fun resultToJson(result: OcrEngine.OcrResult): String {
        val sb = StringBuilder()
        sb.append("{\"imageWidth\":").append(result.imageWidth)
        sb.append(",\"imageHeight\":").append(result.imageHeight)
        sb.append(",\"elapsedMs\":").append(result.elapsedMs)
        sb.append(",\"bubbles\":[")
        result.bubbles.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(b.id)
            sb.append(",\"rect\":[")
                .append(b.rect.left).append(',')
                .append(b.rect.top).append(',')
                .append(b.rect.right).append(',')
                .append(b.rect.bottom).append(']')
            sb.append(",\"score\":").append(b.avgScore)
            sb.append(",\"text\":").append(jsonStr(b.text))
            sb.append(",\"lines\":[")
            b.lines.forEachIndexed { j, l ->
                if (j > 0) sb.append(',')
                sb.append("{\"text\":").append(jsonStr(l.text))
                sb.append(",\"score\":").append(l.score)
                sb.append(",\"rect\":[")
                    .append(l.rect.left).append(',')
                    .append(l.rect.top).append(',')
                    .append(l.rect.right).append(',')
                    .append(l.rect.bottom).append("]}")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Gabungan daftar bubble per gambar (sudah difilter user).
     * Satu gambar → langsung baris-barisnya; banyak gambar → pakai header.
     */
    fun batchBubblesToTxt(
        lists: List<List<BubbleGrouper.Bubble>>,
        prefix: BubblePrefix = BubblePrefix.NONE,
    ): String {
        if (lists.size == 1) return bubblesToTxt(lists[0], prefix)
        return lists.mapIndexed { i, bs ->
            "=== Gambar ${i + 1} ===\n" + bubblesToTxt(bs, prefix)
        }.joinToString("\n")
    }

    /** Gabungan banyak gambar: tiap gambar diberi header "=== Gambar i ===". */
    fun batchToTxt(
        results: List<OcrEngine.OcrResult>,
        prefix: BubblePrefix = BubblePrefix.NONE,
    ): String = batchBubblesToTxt(results.map { it.bubbles }, prefix)

    /** JSON array per-gambar (struktur tiap item = resultToJson). */
    fun batchToJson(results: List<OcrEngine.OcrResult>): String {
        // Bungkus tiap objek resultToJson ke dalam "images".
        // resultToJson mengembalikan objek {...}; sisipkan "index" setelah kurung buka.
        val items = results.mapIndexed { i, r ->
            resultToJson(r).replaceFirst("{", "{\"index\":$i,")
        }
        return "{\"count\":${results.size},\"images\":[${items.joinToString(",")}]}"
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Write text to cache dir and return file Uri (no FileProvider needed). */
    fun writeCache(ctx: Context, fileName: String, content: String): Uri {
        val f = File(ctx.cacheDir, fileName)
        f.writeText(content, Charsets.UTF_8)
        return Uri.fromFile(f)
    }
}
