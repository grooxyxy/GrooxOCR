# Folder ini diisi OTOMATIS oleh GitHub Action saat build.
# Workflow .github/workflows/android.yml mengunduh 5 file ONNX ke sini
# SEBELUM menjalankan Gradle, sehingga model ter-bundel di dalam APK:
#
#   ppocrv6_small_det.onnx    <- PP-OCRv6_small_det_onnx/inference.onnx          (~9.8 MB) — detektor semua bahasa
#   ppocrv6_small_rec.onnx    <- PP-OCRv6_small_rec_onnx/inference.onnx          (~21 MB)  — auto 中文・日本語
#   korean_v5_mobile_rec.onnx <- korean_PP-OCRv5_mobile_rec_onnx/inference.onnx  (~13.4 MB) — Korea
#   en_v5_mobile_rec.onnx     <- en_PP-OCRv5_mobile_rec_onnx/inference.onnx      (~7.8 MB)  — English
#   latin_v5_mobile_rec.onnx  <- latin_PP-OCRv5_mobile_rec_onnx/inference.onnx   (~8.0 MB)  — Latin ES/VI/ID
#
# Jangan commit file .onnx (besar); cukup .gitkeep ini.
# Build lokal tanpa CI: unduh manual kelima URL di atas ke folder ini.
