# Folder ini diisi OTOMATIS oleh GitHub Action saat build.
# Workflow .github/workflows/android.yml mengunduh 3 file ONNX ke sini
# SEBELUM menjalankan Gradle, sehingga model ter-bundel di dalam APK:
#
#   ppocrv6_small_det.onnx   <- PP-OCRv6_small_det_onnx/inference.onnx   (~9.8 MB)
#   ppocrv6_small_rec.onnx   <- PP-OCRv6_small_rec_onnx/inference.onnx   (~21 MB)
#   korean_v5_mobile_rec.onnx <- korean_PP-OCRv5_mobile_rec_onnx/inference.onnx (~13.4 MB)
#
# Jangan commit file .onnx (besar); cukup .gitkeep ini.
# Build lokal tanpa CI: unduh manual ketiga URL di atas ke folder ini.
