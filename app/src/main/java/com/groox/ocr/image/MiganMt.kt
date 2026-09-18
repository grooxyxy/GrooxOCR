package com.groox.ocr.image

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * MiGAN pipeline v2 (andraniksargsyan/migan) untuk background BERTEKSTUR.
 * Input: image uint8 RGB [1,3,H,W] + mask uint8 [1,1,H,W]
 * (255 = pertahankan, 0 = inpaint). Output: uint8 RGB.
 * File diunduh CI ke assets/migan/ → filesDir/migan/.
 */
class MiganMt(private val appContext: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val lock = Any()

    fun modelFile(): File = File(File(appContext.filesDir, "migan").apply { mkdirs() }, "migan_pipeline_v2.onnx")

    fun ready(): Boolean = modelFile().length() > 5_000_000

    /** Salin dari assets bila belum ada (tanpa internet). */
    fun ensureAssets() {
        val dst = modelFile()
        if (ready()) return
        try {
            appContext.assets.open("migan/migan_pipeline_v2.onnx").use { ins ->
                dst.outputStream().use { ins.copyTo(it) }
            }
        } catch (t: Throwable) {
            dst.delete()
            throw RuntimeException("assets/migan hilang di APK. $t")
        }
        if (!ready()) throw RuntimeException("Model MiGAN belum terbundel. Build ulang via Action.")
    }

    @Synchronized
    private fun sess(): OrtSession {
        session?.let { return it }
        ensureAssets()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(modelFile().absolutePath, opts).also { session = it }
    }

    /**
     * Inpaint satu crop. [rgb] = piksel ARGB crop, [removeMask] = true pada
     * piksel teks (di-inpaint). Mengembalikan piksel ARGB hasil seukuran crop.
     * Crop dibatasi sisi-maks 512 (resize) lalu dikembalikan ke ukuran semula.
     */
    fun inpaintCrop(rgb: IntArray, w: Int, h: Int, removeMask: BooleanArray): IntArray {
        require(rgb.size == w * h && removeMask.size == w * h)
        val s = sess()
        val inNames = s.inputNames.toList()
        // Nama input sesuai dok pipeline: "image" + "mask" (tahan urutan apa pun).
        val imgName = inNames.firstOrNull { it.contains("image", ignoreCase = true) } ?: inNames[0]
        val mskName = inNames.firstOrNull { it.contains("mask", ignoreCase = true) }
            ?: inNames.firstOrNull { it != imgName } ?: imgName

        // Skala ke sisi-maks 512 (jaga aspek).
        val scale = minOf(1f, 512f / maxOf(w, h))
        val sw = (w * scale).roundToInt().coerceAtLeast(32)
        val sh = (h * scale).roundToInt().coerceAtLeast(32)
        val small = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(rgb, w, h, Bitmap.Config.ARGB_8888), sw, sh, true
        )
        // Mask kecil via nearest (biner).
        val smallMask = BooleanArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val sx = (x / scale).toInt().coerceIn(0, w - 1)
                val sy = (y / scale).toInt().coerceIn(0, h - 1)
                smallMask[y * sw + x] = removeMask[sy * w + sx]
            }
        }
        try {
            val spx = IntArray(sw * sh)
            small.getPixels(spx, 0, sw, 0, 0, sw, sh)
            val imgBytes = ByteArray(sw * sh * 3)
            val mskBytes = ByteArray(sw * sh)
            for (i in spx.indices) {
                val p = spx[i]
                imgBytes[i] = ((p shr 16) and 0xFF).toByte()
                imgBytes[sw * sh + i] = ((p shr 8) and 0xFF).toByte()
                imgBytes[sw * sh * 2 + i] = (p and 0xFF).toByte()
                // Pipeline: 255 = keep, 0 = inpaint.
                mskBytes[i] = if (smallMask[i]) 0 else 255.toByte()
            }
            val feeds = linkedMapOf<String, OnnxTensor>()
            try {
                feeds[imgName] = uint8Tensor(imgBytes, longArrayOf(1, 3, sh.toLong(), sw.toLong()))
                if (mskName != imgName) {
                    feeds[mskName] = uint8Tensor(mskBytes, longArrayOf(1, 1, sh.toLong(), sw.toLong()))
                }
                s.run(feeds).use { out ->
                    var t: OnnxTensor? = null
                    for (e in out) {
                        if (e.value is OnnxTensor) {
                            t = e.value as OnnxTensor
                            break
                        }
                    }
                    val tensor = t ?: throw RuntimeException("MiGAN tanpa output tensor")
                    val ob = tensor.byteBuffer
                    val oarr = ByteArray(ob.remaining())
                    ob.get(oarr)
                    val shape = tensor.info.shape
                    // Bentuk umum [1,3,H,W]; bila 512 tetap, skala kembali.
                    var ow = sw
                    var oh = sh
                    var off = 0
                    if (shape.size == 4) {
                        oh = shape[2].toInt()
                        ow = shape[3].toInt()
                    }
                    val expected = ow * oh * 3
                    val data = if (oarr.size >= expected) oarr else oarr + ByteArray(expected - oarr.size)
                    val outBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                    val opx = IntArray(ow * oh)
                    for (k in opx.indices) {
                        val r = data[k].toInt() and 0xFF
                        val g = data[ow * oh + k].toInt() and 0xFF
                        val b = data[ow * oh * 2 + k].toInt() and 0xFF
                        opx[k] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    }
                    outBmp.setPixels(opx, 0, ow, 0, 0, ow, oh)
                    // Kembalikan ke ukuran crop bila model me-resize.
                    val back = if (ow != sw || oh != sh) {
                        val r = Bitmap.createScaledBitmap(outBmp, sw, sh, true)
                        outBmp.recycle()
                        r
                    } else outBmp
                    try {
                        val bpx = IntArray(sw * sh)
                        back.getPixels(bpx, 0, sw, 0, 0, sw, sh)
                        // Upscale ke ukuran crop asli + tempel HANYA piksel mask.
                        val full = Bitmap.createScaledBitmap(
                            Bitmap.createBitmap(bpx, sw, sh, Bitmap.Config.ARGB_8888), w, h, true
                        )
                        try {
                            val fpx = IntArray(w * h)
                            full.getPixels(fpx, 0, w, 0, 0, w, h)
                            val res = rgb.copyOf()
                            for (k in res.indices) {
                                if (removeMask[k]) res[k] = fpx[k]
                            }
                            return res
                        } finally {
                            full.recycle()
                        }
                    } finally {
                        back.recycle()
                    }
                }
            } finally {
                feeds.values.forEach { try { it.close() } catch (_: Exception) {} }
            }
        } finally {
            small.recycle()
        }
    }

    private fun uint8Tensor(data: ByteArray, shape: LongArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.UINT8)
    }

    fun close() {
        synchronized(lock) {
            try { session?.close() } catch (_: Exception) {}
            session = null
        }
    }
}
