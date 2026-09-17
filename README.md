# GrooxOCR — OCR + PDF Manhwa/Manga untuk Android

APK mobile **Kotlin + Jetpack Compose** (minSdk 28 / Android 9+) dengan dua tab:

- **Tab OCR**: **PP-OCRv6-small sebagai model utama** untuk komik strip panjang
  (720×16000 bahkan lebih), input **JPG / PNG / WebP**, output **per-bubble**
  (bukan per-baris). **Pilih 1 atau banyak gambar** — OCR berjalan berurutan,
  hasil per gambar bisa dijelajah (‹ ›) dan disalin/dibagikan gabungan.
  Tiap bubble = 1 baris; awalan baris bisa dipilih (tanpa / `-` / `•` / `>` /
  nomor); tiap bubble bisa dihapus, dan semua teks tampil bisa disalin sekaligus.
- **Tab PDF**: **gambar → PDF** (tiap gambar 1 halaman fit-width, JPEG asli
  ditempel tanpa re-encode → tetap tajam; strip panjang dipecah otomatis jadi
  halaman scrollable) + **kompres PDF** + **PDF terkunci password** (enkripsi
  standar, dibuka semua reader) + **PDF → JPG**. Semua output bisa di-rename.
- **Tab Gambar**: **gabung vertikal** (lebar disamakan, panjang & kualitas
  custom, output JPG + ZIP), **pisah vertikal** (jumlah/tinggi custom),
  **smart watermark** (teks/logo, hindari area ramai & bubble, pratinjau).
  Full offline.
- **Tab Terjemah**: offline (MarianMT INT8) — ketik/paste atau file TXT.
  KO→EN, EN→ID, KO→ID (rantai). Salin/bagikan/simpan TXT.

## Arsitektur model

| Peran | Model | Ukuran ONNX | Bahasa |
|---|---|---|---|
| Deteksi (utama) | `PP-OCRv6_small_det` (ONNX) | ~9.8 MB | language-agnostic |
| Rekognisi (utama) | `PP-OCRv6_small_rec` (ONNX) | ~21 MB | EN + ZH-CN + ZH-TW + JA + 46 Latin |
| Rekognisi (pendamping Korea) | `korean_PP-OCRv5_mobile_rec` (ONNX) | ~13.4 MB | KO + EN |

> Fakta penting: dict `PP-OCRv6_small_rec` (18.708 karakter) **tidak mengandung
> Hangul** (0 karakter 가–힣). Diskusi resmi PaddleOCR (#18249) juga
> mengonfirmasi Korea belum termasuk dalam 50 bahasa v6 dan workaround resmi
> adalah **det v6 + rec `korean_PP-OCRv5_mobile_rec`**. Karena itu repo ini
> memakai **v6-small sebagai utama** persis seperti permintaan, plus modul
> Korea v5-mobile **hanya untuk Hangul**. Tanpa modul ini, manhwa Korea tidak
> akan terbaca.

Sumber model (diunduh OTOMATIS oleh GitHub Action ke `assets/models/`
sebelum build — ter-bundel di APK, tanpa unduhan runtime, tanpa internet):

- https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx
- https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx
- https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx

Dict dibundel di `app/src/main/assets/`:
`dict_v6_small.txt` (18.708), `dict_korean_v5.txt` (11.945).

## Fitur utama

- **Long-strip tiling**: `BitmapRegionDecoder` + tile 720×1600 overlap 200px,
  offset global, NMS lintas-tile. Tidak pernah memuat 720×16000 penuh
  (~46 MB ARGB) sekaligus → aman dari OOM.
- **Deteksi DBNet v6**: resize `limit_side` (960/1280/1536), normalize
  ImageNet, pad kelipatan 32, postprocess `DBPostProcess`
  (`thresh=0.2`, `box_thresh=0.5`, `unclip_ratio=1.4`).
- **Rekognisi CTC**: crop per-box, resize tinggi 48 jaga rasio
  (`(x/255-0.5)/0.5`), decode greedy + skor rata-rata.
- **Dual-recognizer + routing bahasa**: mode `V6_ONLY`, `KOREAN_ONLY`,
  `AUTO` (default). `AUTO` menjalankan v6 + korean dan memilih skor
  tertinggi per-box; jika teks mengandung Hangul, otomatis menang korean.
- **Bubble grouping** (`BubbleGrouper`, union-find): dilasi box
  (12px / 8% tinggi), gabung komponen bersentuhan, urut baca
  top-to-bottom (+ left-to-right / right-to-left untuk manga),
  gabung teks per-bubble dengan spasi/newline.
- **UI Compose**: pilih gambar (SAF, jpg/png/webp), salin model dari APK
  dengan progres, atur bahasa/deteksi/bubble, overlay box, daftar bubble
  (tap → copy), ekspor TXT/JSON, share. Full offline.
- **ONNX Runtime Android**: `onnxruntime-android:1.22.0`, 4 thread CPU,
  coba NNAPI lalu fallback CPU, fp32.
- **PDF offline tanpa dependensi**: `pdf/PdfWriter.kt` (writer PDF 1.4 minimal,
  embed JPEG langsung), `pdf/ImageToPdf.kt` (multi-gambar → PDF fit-width),
  `pdf/PdfCompressor.kt` (kompres PDF via `PdfRenderer`), `pdf/PdfShare.kt`
  (bagikan via FileProvider, simpan ke Download/GrooxOCR via MediaStore).

## Struktur

```
app/src/main/java/com/groox/ocr/
  MainActivity.kt, GrooxOcrApp.kt
  data/ModelManager.kt, OcrModels.kt, ImageTiling.kt
  engine/OrtSessions.kt, DetPreprocess.kt, DetPostprocess.kt,
         RecPreprocess.kt, CtcDecoder.kt, DictLoader.kt,
         BubbleGrouper.kt, OcrEngine.kt
  ui/theme/*, ui/screens/HomeScreen.kt, ui/screens/ResultScreen.kt,
  ui/screens/PdfScreen.kt, ui/components/*,
  ui/viewmodel/OcrViewModel.kt, ui/viewmodel/PdfViewModel.kt
  pdf/PdfWriter.kt, ImageToPdf.kt, PdfCompressor.kt, PdfShare.kt,
  PdfCrypt.kt, PdfToJpg.kt, ZipKit.kt
  image/CombineImage.kt, SplitImage.kt, Watermark.kt
  ui/screens/ImageToolsScreen.kt, ui/viewmodel/ImageToolsViewModel.kt
  util/ExportUtils.kt
```

## Build & rilis

APK debug + release dibangun via GitHub Actions (`.github/workflows/android.yml`).
Artefak: `GrooxOCR-debug` (`app-debug.apk`), `GrooxOCR-release`
(`app-release.apk`, signed), `GrooxOCR-release-keystore` (JKS).

Rilis memakai keystore `keystore/groox-release.jks` (alias `groox`,
store/key password `161105`, RSA-2048, 30 tahun) yang dibuat otomatis oleh CI.
**Penting**: update APK butuh tanda tangan yang sama — unduh JKS dari artefak
dan simpan aman; jangan commit ke repo publik. Override via env
`KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`.

Lokal:

```bash
./gradlew :app:assembleDebug
```

Lihat `docs/` untuk detail pipeline, tiling, dan bubble grouping.

## Lisensi

Kode aplikasi MIT. Unwatermark port dari watermark remover v1.4.0 (browser).
Model PaddleOCR Apache-2.0 (milik Baidu/PaddlePaddle).

Channel utama: https://t.me/VasiliasPV — develop by @AnergiaPV.
Sampel watermark: [WM Jjaptoon](https://drive.google.com/drive/folders/1ZPAFYEdnS7V5eJeNP5AcrDDhJ1XjY6bk?usp=drive_link) ·
[WM Korea](https://drive.google.com/drive/folders/17dWWAU0LzIUxjNn3p1JA5VqLZwSq7o_0?usp=drive_link).
