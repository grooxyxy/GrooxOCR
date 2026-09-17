# Terjemahan offline (MarianMT INT8)

Tab Terjemah: ketik/paste manual atau ambil file TXT → KO→EN, EN→ID,
KO→ID (rantai KO→EN lalu EN→ID). Hasil: salin / bagikan / simpan TXT.

## Model

Xenova opus-mt (Marian 6+6 layer, d=512), varian **INT8**
(`encoder_model_int8.onnx` + `decoder_model_merged_int8.onnx`), diunduh
workflow ke `assets/mt/{ko-en,en-id}/` bersama `tokenizer.json`, lalu
disalin ke `filesDir/mt/` saat pertama dipakai. Tanpa internet.

## Cara kerja (`mt/`)

- `UnigramTokenizer.kt`: parse `tokenizer.json` (org.json bawaan Android),
  NFKC + Metaspace, Viterbi best-path + fallback unk, EOS di akhir sumber.
- `MarianMt.kt`: encoder 1x + decoder-merged greedy loop (start = pad id,
  stop = EOS/maks 128 token). Nama input/output dibaca dinamis; pasangan
  past/present dicocokkan dari nama tensor (tahan variasi ekspor).
- `Translator.kt`: pecah per baris (baris kosong dipertahankan, baris
  >60 kata dipotong), chaining KO→ID.

Catatan jujur: greedy (bukan beam-4/6 bawaan config) demi kecepatan HP;
kualitas sedikit di bawah server, tapi cocok untuk teks bubble pendek.
Baris super-panjang sebaiknya dipecah manual per kalimat.
