# Gambar: gabung / pisah / watermark (+ PDF kunci & PDF→JPG)

## PDF terkunci (`pdf/PdfCrypt.kt`)

Enkripsi standar PDF V=2/R=3 (RC4 128-bit, §3.5): password user → O/U/ID +
file key (MD5), semua stream (konten + gambar) dienkripsi per-objek.
Dibuka Adobe/Chrome/MuPDF dengan password. Isi kolom password saat
buat/kompres PDF; kosong = tanpa kunci.

## PDF → JPG (`pdf/PdfToJpg.kt`)

Render per halaman via `PdfRenderer` (Hemat 720/q75, Tajam 1080/q90,
Besar 1440/q95) → file JPG + ZIP.

## Rename semua output

Setiap kartu hasil punya kolom nama file. Berlaku saat Bagikan/Simpan:
`ZipKit.ensureName/ensureBaseNames` (sanitasi karakter, pola `base_p1.jpg`,
ZIP ikut dirakit ulang agar nama entri sinkron).

## Gabung vertikal (`image/CombineImage.kt`)

Gambar ditumpuk ke bawah dengan lebar disamakan (480/720/1080/1440px).
Panjang maks per file (8000–30000px) + kualitas JPEG diatur user.
Render per chunk + pita 2000px via `BitmapRegionDecoder` → strip
720×60000+ aman memori. Output JPG + ZIP.

## Pisah vertikal (`image/SplitImage.kt`)

Mode jumlah (2–10) atau tinggi per bagian (px). Murni region-decode
(tanpa full-decode). Output JPG + ZIP.

## Smart Watermark (`image/Watermark.kt`)

Beda dari referensi web (skor selisih-tepi): skor VARIANSI luminansi per sel
pada analisis ±360px + penalti gelembung (terang dominan + sedikit tinta =
dialog). Kandidat skor terkecil yang saling berjauhan.
Fitur: watermark TEKS (warna, pill, bayangan) atau LOGO, mode
Smart/4-sudut/Tengah/Ubin-diagonal, blend Normal/Multiply/Screen,
jumlah/ukuran/opasitas/rotasi/margin, pratinjau, output JPG + ZIP.

## Unwatermark (`image/Unwatermark.kt`)

Port setia `watermark remover html v1.4.0` (tidak diubah): posisi anchor +
geser user (drag pratinjau / stepper ±1/±10px, presisi penuh), rumus
reverse-blend B = (I − αW)/(1−α) dengan alpha-adjust + ambang transparan/opak,
smoothing piksel opak, perataan whole-pixel & subpixel otomatis, smoothing
tepi + brightness (termasuk faktor acak referensi). OOB berperilaku seperti
JS (NaN → 0). Yang tidak diport: filter noise-JPEG (berbasis filter CSS
canvas, default mati di referensi). Sampel watermark: WM Jjaptoon / WM Korea
(link Drive di aplikasi). Output JPG + ZIP + rename + pratinjau Normal/Difference.
