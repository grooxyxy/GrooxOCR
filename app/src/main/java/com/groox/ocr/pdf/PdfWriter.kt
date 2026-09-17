package com.groox.ocr.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Minimal PDF 1.4 writer — tanpa dependensi, full offline.
 *
 * Tiap gambar di-embed sebagai XObject JPEG (DCTDecode):
 * - JPEG asli disisipkan byte-per-byte (tanpa re-encode → tajam & kecil).
 * - Bitmap (PNG/WebP/render PDF) dikompres ke JPEG dulu oleh pemanggil.
 * - Gambar mengisi PENUH lebar halaman (fit-width canvas), tinggi proporsional.
 * - Halaman yang lebih tinggi dari [MAX_PAGE_H_PT] dipecah otomatis
 *   (batas praktis reader ±14400pt) sehingga strip 720×16000 jadi
 *   beberapa halaman berurutan yang bisa di-scroll.
 */
object PdfWriter {

    const val MAX_PAGE_H_PT = 12000f

    data class JpegPage(val imgW: Int, val imgH: Int, val jpeg: ByteArray)

    /**
     * @param pages halaman sudah-final (sudah dipecah bila perlu).
     * @param pageWpt lebar halaman dalam point.
     * @param password bila diisi → PDF dikunci (enkripsi standar V2/R3,
     * dibuka semua reader dengan password ini).
     */
    fun build(pages: List<JpegPage>, pageWpt: Float, password: String? = null): ByteArray {
        require(pages.isNotEmpty()) { "Tidak ada halaman" }
        val crypt = if (!password.isNullOrEmpty()) PdfCrypt.prepare(password) else null
        fun enc(objNum: Int, data: ByteArray): ByteArray =
            if (crypt == null) data
            else PdfCrypt.encryptStream(crypt.fileKey, objNum, data)

        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun w(bytes: ByteArray) = out.write(bytes)

        w("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        var objNum = 0
        fun beginObj(): Int {
            objNum++
            offsets.add(out.size().toLong())
            w("$objNum 0 obj\n")
            return objNum
        }

        // Objek 1: Catalog, 2: Pages (kids diisi setelah nomor halaman tahu).
        val pageObjNums = mutableListOf<Int>()
        val firstPageObj = 3
        // Nomor objek halaman: tiap halaman pakai 3 objek (page, contents, image).
        for (i in pages.indices) pageObjNums.add(firstPageObj + i * 3)
        var totalObjs = 2 + pages.size * 3
        // Objek Encrypt selalu terakhir bila dikunci.
        val encryptObj = if (crypt != null) totalObjs + 1 else -1
        if (crypt != null) totalObjs += 1

        beginObj() // 1
        w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObj() // 2
        w("<< /Type /Pages /Kids [")
        pageObjNums.forEach { w("$it 0 R ") }
        w("] /Count ${pages.size} >>\nendobj\n")

        pages.forEachIndexed { i, pg ->
            val pageHpt = pageWpt * pg.imgH / pg.imgW.toFloat()
            val contentObj = pageObjNums[i] + 1
            val imageObj = pageObjNums[i] + 2
            beginObj() // page
            w(
                "<< /Type /Page /Parent 2 0 R " +
                    "/MediaBox [0 0 ${fmt(pageWpt)} ${fmt(pageHpt)}] " +
                    "/Resources << /XObject << /Im$i $imageObj 0 R >> >> " +
                    "/Contents $contentObj 0 R >>\nendobj\n"
            )
            val content =
                "q\n${fmt(pageWpt)} 0 0 ${fmt(pageHpt)} 0 0 cm\n/Im$i Do\nQ\n"
            val contentBytes = enc(contentObj, content.toByteArray(Charsets.US_ASCII))
            beginObj() // contents
            w("<< /Length ${contentBytes.size} >>\nstream\n")
            w(contentBytes)
            w("\nendstream\nendobj\n")
            beginObj() // image
            val imgBytes = enc(imageObj, pg.jpeg)
            w(
                "<< /Type /XObject /Subtype /Image " +
                    "/Width ${pg.imgW} /Height ${pg.imgH} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                    "/Filter /DCTDecode /Length ${imgBytes.size} >>\nstream\n"
            )
            w(imgBytes)
            w("\nendstream\nendobj\n")
        }

        if (crypt != null) {
            beginObj() // encrypt dict
            w(
                "<< /Filter /Standard /V 2 /R 3 /Length 128 " +
                    "/O <${crypt.oHex}> /U <${crypt.uHex}> /P ${PdfCrypt.PERMS} >>\nendobj\n"
            )
        }

        val xrefPos = out.size().toLong()
        w("xref\n0 ${totalObjs + 1}\n")
        w("0000000000 65535 f \n")
        offsets.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size ${totalObjs + 1} /Root 1 0 R")
        if (crypt != null) {
            w(" /Encrypt $encryptObj 0 R /ID [<${crypt.idHex}> <${crypt.idHex}>]")
        }
        w(" >>\nstartxref\n$xrefPos\n%%EOF")
        return out.toByteArray()
    }

    private fun fmt(f: Float): String =
        if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f)

    /**
     * Pecah JpegPage yang terlalu tinggi menjadi beberapa halaman penuh.
     * Re-encode segmen pada [quality] (hanya dipakai bila halaman melebihi batas).
     */
    fun splitIfTall(page: JpegPage, pageWpt: Float, quality: Int = 95): List<JpegPage> {
        val pageHpt = pageWpt * page.imgH / page.imgW.toFloat()
        if (pageHpt <= MAX_PAGE_H_PT) return listOf(page)
        val segs = kotlin.math.ceil((pageHpt / MAX_PAGE_H_PT).toDouble()).toInt()
        val full = BitmapFactory.decodeByteArray(page.jpeg, 0, page.jpeg.size)
            ?: return listOf(page)
        try {
            val segH = full.height / segs
            val out = mutableListOf<JpegPage>()
            for (i in 0 until segs) {
                val y0 = i * segH
                val h = if (i == segs - 1) full.height - y0 else segH
                if (h <= 0) continue
                val part = Bitmap.createBitmap(full, 0, y0, full.width, h)
                try {
                    out.add(JpegPage(part.width, part.height, jpegBytes(part, quality)))
                } finally {
                    part.recycle()
                }
            }
            return out.ifEmpty { listOf(page) }
        } finally {
            full.recycle()
        }
    }

    fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        // JPEG tak mendukung alfa → flatten ke putih.
        val flat = if (bmp.hasAlpha()) {
            val c = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(c)
            cv.drawColor(android.graphics.Color.WHITE)
            cv.drawBitmap(bmp, 0f, 0f, null)
            c
        } else bmp
        try {
            flat.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        } finally {
            if (flat !== bmp) flat.recycle()
        }
        return bos.toByteArray()
    }
}
