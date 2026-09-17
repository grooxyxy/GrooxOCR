package com.groox.ocr.pdf

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP (java.util, tanpa dependensi) + rename file hasil yang aman.
 * Semua output (PDF, JPG, ZIP) bisa di-rename user sebelum dibagikan/disimpan.
 */
object ZipKit {

    /** Bungkus file-file menjadi satu ZIP di direktori yang sama. */
    fun zip(sources: List<File>, zipFile: File): File {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            sources.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /** Bersihkan nama file: huruf/angka/spasi/_/-/. ; maks 60 char. */
    fun sanitize(base: String, fallback: String = "GrooxOCR"): String {
        val clean = base.trim()
            .replace(Regex("[^A-Za-z0-9 _.-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .trimEnd('.', ' ')
        return clean.ifEmpty { fallback }
    }

    /**
     * Rename satu file ke [base].[ext] (tanpa ext di base).
     * Fallback salin-hapus bila renameTo gagal (beda mount).
     */
    fun ensureName(file: File, base: String): File {
        val want = File(file.parentFile, sanitize(base) + "." + file.extension)
        if (want.absolutePath == file.absolutePath) return file
        if (want.exists()) want.delete()
        return if (file.renameTo(want)) want else {
            file.copyTo(want, overwrite = true)
            file.delete()
            want
        }
    }

    /**
     * Rename banyak file ikut pola: base.ext (bila 1) atau base_p1.ext, base_p2.ext…
     */
    fun ensureBaseNames(files: List<File>, base: String): List<File> {
        val b = sanitize(base)
        if (files.size == 1) return listOf(ensureName(files[0], b))
        return files.mapIndexed { i, f ->
            ensureName(f, "${b}_p${i + 1}")
        }
    }
}
