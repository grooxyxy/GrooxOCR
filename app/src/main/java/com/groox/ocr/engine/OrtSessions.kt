package com.groox.ocr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections

/**
 * Owns ORT environment + up to 3 sessions (det, rec-v6, rec-ko).
 * Sessions are opened lazily and reused across tiles/boxes.
 *
 * Threading: 4 intra-op threads, CPU (XNNPACK). NNAPI is attempted first
 * for det (large conv model) with graceful fallback to CPU — NNAPI drivers
 * on Android 9 devices are often broken for DBNet-style ops.
 */
class OrtSessions : AutoCloseable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var det: OrtSession? = null
    private var recV6: OrtSession? = null
    private var recKo: OrtSession? = null
    private val lock = Any()

    fun detSession(model: File): OrtSession = synchronized(lock) {
        det ?: open(model, tryNnapi = true).also { det = it }
    }

    fun recV6Session(model: File): OrtSession = synchronized(lock) {
        recV6 ?: open(model, tryNnapi = false).also { recV6 = it }
    }

    fun recKoSession(model: File): OrtSession = synchronized(lock) {
        recKo ?: open(model, tryNnapi = false).also { recKo = it }
    }

    private fun open(model: File, tryNnapi: Boolean): OrtSession {
        require(model.exists()) { "Model tidak ditemukan: ${model.absolutePath}" }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        if (tryNnapi) {
            try {
                opts.addNnapi()
                Log.i("OrtSessions", "NNAPI enabled for ${model.name}")
            } catch (t: Throwable) {
                Log.w("OrtSessions", "NNAPI unavailable, CPU fallback: $t")
            }
        }
        return env.createSession(model.absolutePath, opts)
    }

    /** Run det: input [1,3,H,W] float → first output float array + its shape. */
    fun runDet(
        session: OrtSession,
        inputName: String,
        data: FloatBuffer,
        shape: LongArray,
    ): Pair<FloatArray, LongArray> {
        OnnxTensor.createTensor(env, data, shape).use { t ->
            session.run(Collections.singletonMap(inputName, t)).use { out ->
                val tensor = out[0].value as OnnxTensor
                @Suppress("UNCHECKED_CAST")
                val arr = (tensor.floatBuffer.array() as? FloatArray)
                    ?: tensorToFloatArray(tensor)
                return arr to tensor.info.shape
            }
        }
    }

    /** Run rec: input [1,3,48,W] float → logits [1,T,C]. */
    fun runRec(
        session: OrtSession,
        inputName: String,
        data: FloatBuffer,
        shape: LongArray,
    ): RecLogits {
        OnnxTensor.createTensor(env, data, shape).use { t ->
            session.run(Collections.singletonMap(inputName, t)).use { out ->
                val tensor = out[0].value as OnnxTensor
                val shapeOut = tensor.info.shape // [1,T,C]
                val arr = tensor.floatBuffer.array() as? FloatArray
                    ?: tensorToFloatArray(tensor)
                val tDim = shapeOut[1].toInt()
                val cDim = shapeOut[2].toInt()
                return RecLogits(arr, tDim, cDim)
            }
        }
    }

    private fun tensorToFloatArray(t: OnnxTensor): FloatArray {
        val buf = t.floatBuffer
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    data class RecLogits(val data: FloatArray, val seqLen: Int, val numClasses: Int)

    override fun close() {
        synchronized(lock) {
            try { det?.close() } catch (_: Exception) {}
            try { recV6?.close() } catch (_: Exception) {}
            try { recKo?.close() } catch (_: Exception) {}
            det = null; recV6 = null; recKo = null
        }
    }
}
