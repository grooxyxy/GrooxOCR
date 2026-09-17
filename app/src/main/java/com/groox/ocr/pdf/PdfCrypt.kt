package com.groox.ocr.pdf

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Enkripsi PDF standar (V=2, R=3, RC4 128-bit) sesuai spesifikasi PDF 1.4 §3.5.
 * Dibuka oleh semua reader umum (Adobe, Chrome, MuPDF) dengan password user.
 * Murni Kotlin, tanpa dependensi.
 */
object PdfCrypt {

    private val PADDING = byteArrayOf(
        0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(),
        0x4E.toByte(), 0x75.toByte(), 0x8A.toByte(), 0x41.toByte(),
        0x64.toByte(), 0x00.toByte(), 0x4E.toByte(), 0x56.toByte(),
        0xFF.toByte(), 0xFA.toByte(), 0x01.toByte(), 0x08.toByte(),
        0x2E.toByte(), 0x2E.toByte(), 0x00.toByte(), 0xB6.toByte(),
        0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
        0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(),
        0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte(),
    )

    /** Izin: semua diizinkan (-4 = 0xFFFFFFFC), tapi tetap butuh password untuk buka. */
    const val PERMS = -4

    data class CryptInfo(
        val oHex: String,
        val uHex: String,
        val idHex: String,
        val fileKey: ByteArray,
    )

    fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** RC4 murni. */
    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var x = 0
        j = 0
        for (k in data.indices) {
            x = (x + 1) and 0xFF
            j = (j + s[x]) and 0xFF
            val t = s[x]; s[x] = s[j]; s[j] = t
            out[k] = (data[k].toInt() xor s[(s[x] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02X", x))
        return sb.toString()
    }

    private fun padPassword(pw: ByteArray): ByteArray {
        val out = ByteArray(32)
        PADDING.copyInto(out)
        pw.copyInto(out, 0, 0, minOf(pw.size, 32))
        return out
    }

    /** Siapkan O/U/ID/fileKey. Owner password acak bila user hanya isi user password. */
    fun prepare(userPassword: String): CryptInfo {
        require(userPassword.isNotEmpty()) { "Password kosong" }
        val userPad = padPassword(userPassword.toByteArray(Charsets.UTF_8))
        val ownerSeed = ByteArray(16)
        SecureRandom().nextBytes(ownerSeed)
        val ownerPad = padPassword(ownerSeed)

        // Algoritma 3.3 (O, R=3): MD5 50x lalu RC4 19x dengan key XOR counter.
        var digest = md5(ownerPad)
        repeat(50) { digest = md5(digest) }
        var o = rc4(digest.copyOf(16), userPad)
        for (i in 1..19) {
            val k = digest.copyOf(16)
            for (b in k.indices) k[b] = (k[b].toInt() xor i).toByte()
            o = rc4(k, o)
        }

        val id = ByteArray(16)
        SecureRandom().nextBytes(id)

        // Algoritma 3.2 (file key): MD5(userPad + O + P_LE32 + ID) → 16 byte.
        val p = PERMS
        val pBytes = byteArrayOf(
            (p and 0xFF).toByte(),
            ((p shr 8) and 0xFF).toByte(),
            ((p shr 16) and 0xFF).toByte(),
            ((p shr 24) and 0xFF).toByte(),
        )
        val fileKey = md5(userPad, o, pBytes, id).copyOf(16)

        // Algoritma 3.5 (U, R=3): MD5(padding + ID) → RC4 19x → +16 byte acak.
        var u = rc4(fileKey, md5(PADDING, id))
        for (i in 1..19) {
            val k = fileKey.copyOf()
            for (b in k.indices) k[b] = (k[b].toInt() xor i).toByte()
            u = rc4(k, u)
        }
        val tail = md5(id, "GrooxOCR".toByteArray(Charsets.US_ASCII)).copyOf(16)
        val uFull = u + tail

        return CryptInfo(hex(o), hex(uFull), hex(id), fileKey)
    }

    /** Algoritma 3.1: kunci objek = MD5(fileKey + objNum[3 LE] + gen[2]) → 16 byte. */
    fun objectKey(fileKey: ByteArray, objNum: Int): ByteArray {
        val ext = byteArrayOf(
            (objNum and 0xFF).toByte(),
            ((objNum shr 8) and 0xFF).toByte(),
            ((objNum shr 16) and 0xFF).toByte(),
            0, 0,
        )
        return md5(fileKey, ext).copyOf(16)
    }

    fun encryptStream(fileKey: ByteArray, objNum: Int, data: ByteArray): ByteArray =
        rc4(objectKey(fileKey, objNum), data)
}
