package com.groox.ocr.data

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Klien AI pasca-OCR — Agnes (OpenAI-compatible chat completions).
 *
 *  Base URL : https://apihub.agnes-ai.com/v1
 *  Model    : agnes-2.5-flash
 *  Endpoint : POST /v1/chat/completions
 *
 * API key & model di-BUNDLE ke APK (konstanta di bawah). Fitur ini satu-satunya
 * yang memakai INTERNET; inferensi OCR sendiri tetap offline penuh.
 *
 * Prompt sistem memaksa model SELALU mengembalikan hasil OCR per dialog,
 * walau teks sumber tidak berada di dalam bubble dialog / kotak narasi.
 */
object AgnesClient {

    const val BASE_URL = "https://apihub.agnes-ai.com/v1"
    const val MODEL = "agnes-2.5-flash"
    private const val ENDPOINT = "https://apihub.agnes-ai.com/v1/chat/completions"
    private const val API_KEY = "sk-XSOMMlS3MQWpmtXzWIYSxR4BipnGHnbFZrMpZieAZi82U6kZ"

    private const val SYSTEM_PROMPT = """
Kamu adalah post-processor hasil OCR komik (manhwa/manga/manhua/webtoon).
Tugasmu: menyusun teks OCR mentah menjadi hasil akhir PER DIALOG.

ATURAN WAJIB:
1. SELALU kembalikan hasil sebagai daftar dialog per balon/ucapan, WALAUPUN
   teks sumber tidak berada di dalam bubble dialog atau kotak narasi.
   Teks melayang, SFX/efek suara, label peta/lokasi, catatan kaki, dan teks
   latar TETAP dikeluarkan sebagai entri dialog tersendiri — jangan dibuang.
2. JANGAN menghapus, meringkas, menambah, atau menerjemahkan teks apa pun.
   Pertahankan bahasa aslinya.
3. Satu dialog per baris, mengikuti urutan baca yang diberikan.
4. Gabungkan potongan baris yang jelas satu ucapan; pisahkan ucapan yang
   jelas berbeda pembicara/balon.
5. Perbaiki hanya kesalahan OCR yang jelas (karakter salah baca); jangan
   mengarang isi.
6. Output HANYA teks dialog final (satu per baris), tanpa komentar, tanpa
   penjelasan, tanpa markdown.
""".trimIndent()

    data class AiResult(
        val text: String,
        val model: String,
        val elapsedMs: Long,
    )

    /**
     * Kirim teks OCR mentah (per bubble/baris) ke agnes-2.5-flash dan
     * kembalikan susunan dialog final. Lempar Exception dengan pesan jelas
     * bila jaringan/API gagal.
     */
    suspend fun refineOcr(rawText: String): AiResult = withContext(Dispatchers.IO) {
        val cleaned = rawText.trim()
        require(cleaned.isNotEmpty()) { "Teks OCR kosong — jalankan OCR dulu." }
        val t0 = System.currentTimeMillis()

        val body = JSONObject()
            .put("model", MODEL)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            "Berikut hasil OCR mentah (satu baris = satu bubble/dialog hasil deteksi). " +
                                "Susun menjadi hasil per dialog sesuai aturan:\n\n$cleaned",
                        ),
                    ),
            )
            .toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $API_KEY")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw RuntimeException("Agnes API HTTP $code: ${resp.take(300)}")
            }
            val content = JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            if (content.isEmpty()) throw RuntimeException("Agnes mengembalikan teks kosong")
            AiResult(content, MODEL, System.currentTimeMillis() - t0)
        } finally {
            conn.disconnect()
        }
    }
}
