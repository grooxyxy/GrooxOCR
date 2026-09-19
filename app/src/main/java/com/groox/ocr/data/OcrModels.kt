package com.groox.ocr.data

/**
 * Model catalogue — 4 bahasa / 4 recognizer:
 *  - Korea        : PP-OCRv5 korean (mobile rec)
 *  - English      : PP-OCRv5 en (mobile rec)
 *  - Auto 中文・日本語 : PP-OCRv6-small (det + rec)
 *  - Latin ES/VI/ID  : PP-OCRv5 latin (mobile rec)
 *
 * Detektor tunggal: PP-OCRv6-small det (dipakai semua mode).
 *
 * Semua model di-BUNDEL ke APK oleh GitHub Action (lihat
 * .github/workflows/android.yml yang mengunduh URL di bawah ke
 * app/src/main/assets/models/ sebelum Gradle build). Tidak ada unduhan runtime.
 */
object OcrModels {
    const val DET_FILE = "ppocrv6_small_det.onnx"
    const val REC_V6_FILE = "ppocrv6_small_rec.onnx"
    const val REC_KO_FILE = "korean_v5_mobile_rec.onnx"
    const val REC_EN_FILE = "en_v5_mobile_rec.onnx"
    const val REC_LATIN_FILE = "latin_v5_mobile_rec.onnx"
    /** Subfolder assets tempat CI menaruh model. */
    const val ASSET_DIR = "models"

    const val DET_URL =
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx"
    const val REC_V6_URL =
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx"
    const val REC_KO_URL =
        "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx"
    const val REC_EN_URL =
        "https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx"
    const val REC_LATIN_URL =
        "https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx"

    // Approximate sizes for progress/validation (bytes).
    const val DET_SIZE = 9_880_512L
    const val REC_V6_SIZE = 21_159_378L
    const val REC_KO_SIZE = 13_418_787L
    const val REC_EN_SIZE = 7_848_423L
    const val REC_LATIN_SIZE = 8_042_023L

    const val DICT_V6_ASSET = "dict_v6_small.txt"
    const val DICT_KO_ASSET = "dict_korean_v5.txt"
    const val DICT_EN_ASSET = "dict_en_v5.txt"
    const val DICT_LATIN_ASSET = "dict_latin_v5.txt"

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
    // 1.6 = default Paddle untuk v5/v6; 1.4 terlalu ketat → tepi baris teks
    // terpotong, spasi antar kata hilang di hasil rekognisi.
    const val DB_UNCLIP_RATIO = 1.6f
    const val DB_MAX_CANDIDATES = 3000

    /** Recognition input: height 48, BGR, width dynamic. */
    const val REC_H = 48
    const val REC_MAX_W = 1024
}

/** Mode bahasa → recognizer yang dipakai (det selalu PP-OCRv6-small). */
enum class RecMode(val label: String) {
    /** PP-OCRv6-small rec — auto Chinese/Japanese (juga basis CJK umum). */
    AUTO_CJK("Auto 中文・日本語 (PP-OCRv6-small)"),
    /** PP-OCRv5 korean rec. */
    KOREAN("Korea (PP-OCRv5 한국어)"),
    /** PP-OCRv5 en rec. */
    ENGLISH("English (PP-OCRv5 EN)"),
    /** PP-OCRv5 latin rec — Spanish/Vietnamese/Indonesian dsb. */
    LATIN("Latin ES/VI/ID (PP-OCRv5)"),
}

/** Reading order inside/across bubbles. */
enum class ReadingOrder(val label: String) {
    TOP_TO_BOTTOM_LTR("Top→Bottom, Left→Right (manhwa/manhua)"),
    TOP_TO_BOTTOM_RTL("Top→Bottom, Right→Left (manga)"),
}

/** Tunable OCR parameters (persisted via SavedState/ViewModel, not DataStore to keep deps small). */
data class OcrParams(
    val recMode: RecMode = RecMode.AUTO_CJK,
    val readingOrder: ReadingOrder = ReadingOrder.TOP_TO_BOTTOM_LTR,
    /** Long side for detection resize. 1280 = good balance for 720-wide strips. */
    val detLongSide: Int = 1280,
    val boxThresh: Float = OcrModels.DB_BOX_THRESH,
    val recThresh: Float = 0.45f, // saring garbage SFX (skor proxy sigmoid rendah)
    /** Bubble grouping: expand boxes before union (px + fraction of height). */
    val bubblePadPx: Int = 12,
    val bubblePadRatio: Float = 0.08f,
    /** Max gap to still merge two lines into one bubble (px, scaled to image). */
    val bubbleMergeGap: Int = 40,
    /** Tile height for long strips (px in original image). */
    val tileHeight: Int = 1600,
    val tileOverlap: Int = 200,
)
