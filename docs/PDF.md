# PDF: gambar → PDF tajam + kompres PDF

Full offline, tanpa dependensi tambahan (framework `PdfRenderer` saja).

## Gambar → PDF (`ImageToPdf` + `PdfWriter`)

- Tiap gambar = **1 halaman penuh selebar kanvas** (fit-width):
  stream konten `q W 0 0 H 0 0 cm /Im Do Q`, gambar mengisi 100% lebar halaman.
- **Tetap tajam**: mode Original menempel byte JPEG asli tanpa re-encode.
  PNG/WebP → JPEG q95 (flatten alfa ke putih).
- Kualitas: Original/tajam (asli, q95 bila re-encode) • Seimbang (≤1080px, q85)
  • Hemat (≤720px, q75).
- Lebar halaman: A4 595pt • 720pt (1:1 untuk strip 720px) • ikut lebar gambar.
- **Strip 720×16000+**: tinggi halaman dibatasi 12000pt (batas aman reader
  ±14400pt); strip lebih tinggi dipecah otomatis jadi halaman-halaman
  berurutan → tetap bisa di-scroll di semua reader.
- Writer PDF 1.4 minimal tulisan tangan (`PdfWriter.kt`): Catalog/Pages/Page,
  XObject DCTDecode, xref + trailer. Tanpa iText/PdfBox (hemat ±10 MB APK).

## Kompres PDF (`PdfCompressor`)

- PDF lama di-render per halaman via `PdfRenderer` pada lebar target
  (1080/720px), lalu dibangun ulang sebagai PDF JPEG fit-width.
- Level: Ringan (1080px q85) • Sedang (720px q75) • Kuat (720px q60).
- Hasil menampilkan ukuran lama → baru + rasio.

## Simpan & bagikan (`PdfShare`)

- **Bagikan**: `ACTION_SEND` + FileProvider (`cache-path`).
- **Simpan**: MediaStore `Download/GrooxOCR` (Android 10+, tanpa permission).
  Android 9: pakai Bagikan.
