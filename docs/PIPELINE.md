# Pipeline OCR (PP-OCRv6-small utama)

```
Gambar (JPG/PNG/WebP, s.d. 720×16000+)
  → probe dimensi (tanpa full-decode)
  → tile 720×1600 overlap 200 (BitmapRegionDecoder)
  → DET v6-small per-tile (limit_side default 1280, normalize ImageNet, pad ×32)
  → DBPostProcess (thresh 0.2, box_thresh ~0.5, unclip 1.4) → box lokal
  → offset ke koordinat global → NMS lintas-tile (0.3)
  → crop per-box langsung dari file (padding 5%/8%)
  → orientasi: box vertikal (h > 1.8w) dirotasi 90°
  → REC ganda: v6-small (EN/ZH/JA/Latin) + korean-v5-mobile (KO/EN)
  → routing AUTO: skor tertinggi menang; Hangul diberi prioritas ±0.25
  → filter recThresh (default 0.3)
  → BubbleGrouper (union-find) → output per-bubble
```

## Kenapa dual-recognizer

Dict `PP-OCRv6_small_rec` (18.708 entri) mengandung **0 Hangul**.
Itu keputusan resmi PaddleOCR (v6 = ZH-CN/ZH-TW/EN/JA + 46 Latin;
diskusi #18249 menyarankan det v6 + rec `korean_PP-OCRv5_mobile_rec`).
Tanpa modul Korea, teks manhwa tidak terbaca — jadi v6-small tetap
**satu-satunya utama**, modul Korea hanya pendamping per-box.

## Detail preprocess

- Deteksi: `DetPreprocess.prepare()` — scale long-side, normalize
  `(x/255-mean)/std` mean `[0.485, 0.456, 0.406]`, std `[0.229, 0.224, 0.225]`,
  NCHW fp32. Nama input ONNX diambil dinamis (`session.inputNames.first()`).
- Rekognisi: `RecPreprocess.prepare()` — tinggi 48 jaga rasio, lebar maks
  1024 (kelipatan 8), norm `(x/255-0.5)/0.5`, NCHW fp32.
- CTC: greedy, blank=0, `dict[i] ↔ class i+1`; indeks di luar dict
  (selisih 18710 vs 18708) di-skip aman.

## ONNX Runtime

`onnxruntime-android:1.22.0`, 4 thread CPU, coba NNAPI untuk deteksi
lalu fallback CPU. Tidak ada unduhan runtime — model disalin dari
`assets/models/` ke `filesDir/models/` saat pertama dibuka.
