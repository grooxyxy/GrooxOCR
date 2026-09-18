# Bersih: hapus teks terpilih (tab Bersih)

Alur: upload 1/banyak gambar → tiling + deteksi PP-OCR (bahasa bisa dipilih:
Auto/V6/Korea) → pratinjau + centang kata → hapus → ZIP (1 file per gambar
input, potongan tile selalu kembali utuh karena inpainting di bitmap penuh)
+ pratinjau sesudah/sebelum.

## Kata, bukan baris

Box baris deteksi dipecah per kata (lebar dibagi rata; CJK tanpa spasi
dipecah per karakter) agar selektif. Metode per kata tertera sebagai badge
dan bisa dioverride global (Otomatis/solid/gradasi/MiGAN).

## Mask presisi

Bukan seluruh box: piksel teks dicari via Otsu di dalam box (teks gelap =
lum < ambang, teks terang = sebaliknya; fallback box penuh bila cakupan
aneh), lalu didilasi 1px. Piksel background dalam box TIDAK disentuh.

## Metode per jenis background (klasifikasi dari ring 10px di luar box)

- **Solid** (std ring rendah): isi warna median ring + feather 2px.
  Setara Telea di area solid, tanpa OpenCV.
- **Gradasi** (cocok bidang linear, sisa kecil): difusi Laplace
  coarse-to-fine (piramida s.d. sisi-min 32, Jacobi 300/80 iterasi per
  kanal). Background bergradasi direkonstruksi mulus — Telea/NS gagal di
  mask besar karena me-blur, difusi tidak. Tanpa model.
- **Tekstur**: MiGAN pipeline v2 uint8 (`andraniksargsyan/migan`,
  dibundel CI): crop kata + margin 48px → maks sisi 512 → mask biner
  (0=inpaint) → tempel HANYA piksel mask. Gagal → fallback difusi gradasi.

## Catatan jujur

- Klasifikasi heuristik (ambang std/residu) — untuk kasus ragu paksa metode
  via dropdown.
- MiGAN Places2 umum (bukan khusus manga); untuk SFX di atas art padat,
  hasil bisa halus-jelek — di situlah override manual membantu.
- Bitmap penuh 720×16000 (~46 MB) dimuat 1 gambar dalam satu waktu
  (largeHeap); region inpainting kecil sehingga aman.
