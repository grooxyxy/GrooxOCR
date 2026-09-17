-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# Keep data classes used for JSON export (reflection-free, but be safe)
-keep class com.groox.ocr.engine.** { *; }
-keep class com.groox.ocr.data.** { *; }
