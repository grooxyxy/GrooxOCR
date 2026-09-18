# Folder ini diisi OTOMATIS oleh GitHub Action saat build.
# Workflow mengunduh MiGAN pipeline v2 (andraniksargsyan/migan) ke sini
# SEBELUM Gradle build, sehingga ter-bundel di APK (tanpa unduhan runtime):
#
#   migan_pipeline_v2.onnx  <- andraniksargsyan/migan (uint8 pipeline)
#
# Jangan commit file .onnx (besar); cukup .gitkeep ini.
# Build lokal tanpa CI: unduh manual URL di atas ke folder ini.
