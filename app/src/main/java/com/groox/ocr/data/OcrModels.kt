package com.groox.ocr.data

/**
 * Model catalogue — PP-OCRv6-small is PRIMARY as requested.
 *
 * Korean is NOT covered by v6-small (dict has 0 Hangul; see README).
 * korean_PP-OCRv5_mobile_rec is a companion ONLY for Hangul routing.
 *
 * Semua model di-BUNDEL ke APK oleh GitHub Action (lihat
 * .github/workflows/android.yml yang mengunduh URL di bawah ke
 * app/src/main/assets/models/ sebelum Gradle build). Tidak ada unduhan runtime.
 */
object OcrModels {
    const val DET_FILE = "ppocrv6_small_det.onnx"
    const val REC_V6_FILE = "ppocrv6_small_rec.onnx"
    const val REC_KO_FILE = "korean_v5_mobile_rec.onnx"
    /** Subfolder assets tempat CI menaruh model. */
    const val ASSET_DIR = "models"

    const val DET_URL =
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx"
    const val REC_V6_URL =
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx"
    const val REC_KO_URL =
        "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx"

    // Approximate sizes for progress/validation (bytes).
    const val DET_SIZE = 9_880_512L
    const val REC_V6_SIZE = 21_159_378L
    const val REC_KO_SIZE = 13_418_787L

    const val DICT_V6_ASSET = "dict_v6_small.txt"
    const val DICT_KO_ASSET = "dict_korean_v5.txt"

    /** PP-OCRv6-small detection input spec (from inference.yml). */
    const val DET_MEAN_R = 0.485f
    const val DET_MEAN_G = 0.456f
    const val DET_MEAN_B = 0.406f
    const val DET_STD_R = 0.229f
    const val DET_STD_G = 0.224f
    const val DET_STD_B = 0.225f

    /** DB postprocess defaults (from PP-OCRv6_small_det inference.yml). */
    const val DB_THRESH = 0.2f
    const val DB_BOX_THRESH = 0.5f // yml=0.45; 0.5 slightly stricter for comics
    const val DB_UNCLIP_RATIO = 1.4f
    const val DB_MAX_CANDIDATES = 3000

    /** Recognition input: height 48, BGR, width dynamic. */
    const val REC_H = 48
    const val REC_MAX_W = 1024
}

/** Language routing for the dual recognizer. */
enum class RecMode(val label: String) {
    /** Only PP-OCRv6-small rec (EN/ZH/JA/Latin). Fastest. */
    V6_ONLY("V6 only (EN・中文・日本語)"),
    /** Only Korean v5-mobile rec (KO/EN). */
    KOREAN_ONLY("Korea only (한국어)"),
    /** Run both, pick higher score per box. Default for manhwa/manga. */
    AUTO("Auto (v6 + Korea)"),
}

/** Reading order inside/across bubbles. */
enum class ReadingOrder(val label: String) {
    TOP_TO_BOTTOM_LTR("Top→Bottom, Left→Right (manhwa/manhua)"),
    TOP_TO_BOTTOM_RTL("Top→Bottom, Right→Left (manga)"),
}

/** Tunable OCR parameters (persisted via SavedState/ViewModel, not DataStore to keep deps small). */
data class OcrParams(
    val recMode: RecMode = RecMode.AUTO,
    val readingOrder: ReadingOrder = ReadingOrder.TOP_TO_BOTTOM_LTR,
    /** Long side for detection resize. 1280 = good balance for 720-wide strips. */
    val detLongSide: Int = 1280,
    val boxThresh: Float = OcrModels.DB_BOX_THRESH,
    val recThresh: Float = 0.3f,
    /** Bubble grouping: expand boxes before union (px + fraction of height). */
    val bubblePadPx: Int = 12,
    val bubblePadRatio: Float = 0.08f,
    /** Max gap to still merge two lines into one bubble (px, scaled to image). */
    val bubbleMergeGap: Int = 40,
    /** Tile height for long strips (px in original image). */
    val tileHeight: Int = 1600,
    val tileOverlap: Int = 200,
)
