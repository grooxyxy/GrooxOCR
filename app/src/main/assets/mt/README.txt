# Folder ini diisi OTOMATIS oleh GitHub Action saat build.
# Workflow mengunduh model MarianMT INT8 (Xenova) ke sini SEBELUM Gradle build:
#
#   ko-en/encoder_int8.onnx            <- Xenova/opus-mt-ko-en onnx/encoder_model_int8.onnx
#   ko-en/decoder_int8.onnx            <- Xenova/opus-mt-ko-en onnx/decoder_model_int8.onnx (langkah-0)
#   ko-en/decoder_with_past_int8.onnx  <- Xenova/opus-mt-ko-en onnx/decoder_with_past_model_int8.onnx
#   ko-en/decoder_merged_int8.onnx     <- fallback bila file terpisah absen
#   ko-en/tokenizer.json               <- Xenova/opus-mt-ko-en tokenizer.json
#   (sama untuk en-id dari Xenova/opus-mt-en-id)
#
# KO→ID = rantai KO→EN→ID (tanpa model ketiga).
# Jangan commit file biner (.onnx di-ignore); cukup .gitkeep/README ini.
# Build lokal tanpa CI: unduh manual URL di atas ke folder ini.
