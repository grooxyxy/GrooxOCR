# Strip panjang & bubble grouping

## Tiling (`ImageTiling`)

- `probe()` baca dimensi via `inJustDecodeBounds` (tanpa alokasi bitmap).
- `planTiles()` belah tinggi jadi tile 1600px + overlap 200px.
  Overlap mencegah teks terpotong di batas tile; duplikat dibuang via NMS global.
- Decode per-tile via `BitmapRegionDecoder` (mendukung JPEG/PNG/WebP di API 28+).
  Bitmap 720×16000 penuh (~46 MB ARGB) **tidak pernah** dimuat sekaligus.
- Lebar > 1440 di-downsample manual (hemat RAM; deteksi me-resize lagi ke
  long-side 960/1280/1536).

Contoh 720×16000, tile 1600/overlap 200 → ±11 tile, tiap tile ±720×1600.

## Bubble grouping (`BubbleGrouper`)

Output PP-OCR level baris; komik butuh level bubble:

1. Ekspansi tiap box baris sebesar `padPx (12) + padRatio (8%) × tinggi`.
2. Union-find: gabung jika box ekspansi bersinggungan, atau gap vertikal
   ≤ `mergeGap` (default 40px, diskala dgn lebar gambar) + overlap horizontal
   > 30% / pusat-x berdekatan (untuk SFX 1 huruf).
3. Urut bubble: top-to-bottom, lalu kiri→kanan (manhwa/manhua) atau
   kanan→kiri (manga, `ReadingOrder`).
4. Teks bubble = baris diurut atas→bawah, digabung `\n`.

Hasil: `OcrResult.bubbles` — siap salin per-bubble atau ekspor TXT/JSON.
