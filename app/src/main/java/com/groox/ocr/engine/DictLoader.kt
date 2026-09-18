package com.groox.ocr.engine

import android.content.Context
import com.groox.ocr.data.OcrModels

/**
 * Loads CTC character dictionaries bundled in assets.
 * Index mapping (Paddle convention): class 0 = blank, dict[i] ↔ class i+1.
 */
object DictLoader {
    fun loadV6(ctx: Context): List<String> = loadAsset(ctx, OcrModels.DICT_V6_ASSET)
    fun loadKorean(ctx: Context): List<String> = loadAsset(ctx, OcrModels.DICT_KO_ASSET)

    private fun loadAsset(ctx: Context, name: String): List<String> {
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { r ->
            // Keep every line verbatim (CJK/emoji must not be trimmed inside).
            // Only strip trailing \r; a truly empty line is kept as empty token
            // but trailing file newline is ignored.
            val lines = r.readLines().map { it.trimEnd('\r') }
            val trimmed = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            // Paddle menaruh token SPASI sebagai baris KOSONG di tengah dict
            // (mis. baris 1749 di dict v6-small). Tanpa konversi ini decoder
            // membuang token kosong -> semua spasi hilang ("idon'thave").
            return trimmed.map { if (it.isEmpty()) " " else it }
        }
    }

    /** True if text contains Hangul syllables/Jamo (U+AC00–U+D7A3, U+1100–U+11FF, U+3130–U+318F). */
    fun containsHangul(s: String): Boolean {
        for (c in s) {
            val v = c.code
            if (v in 0xAC00..0xD7A3 || v in 0x1100..0x11FF || v in 0x3130..0x318F) return true
        }
        return false
    }
}
