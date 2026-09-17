package com.groox.ocr.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.image.CombineImage
import com.groox.ocr.image.SplitImage
import com.groox.ocr.image.Unwatermark
import com.groox.ocr.image.Watermark
import com.groox.ocr.pdf.ZipKit
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ImgItem(val uri: Uri, val w: Int, val h: Int)
data class ToolResult(var images: List<File>, var zip: File, val info: String)

sealed interface ToolUi {
    data object Idle : ToolUi
    data class Working(val stage: String, val done: Int, val total: Int) : ToolUi
    data class Done(val kind: String, val result: ToolResult) : ToolUi
    data class Error(val message: String) : ToolUi
}

/** Gabung / Pisah / Watermark — semua lokal, output JPG + ZIP yang bisa di-rename. */
class ImageToolsViewModel : ViewModel() {

    private val _ui = MutableStateFlow<ToolUi>(ToolUi.Idle)
    val ui: StateFlow<ToolUi> = _ui

    // ---- gabung ----
    private val _cbImages = MutableStateFlow<List<ImgItem>>(emptyList())
    val cbImages: StateFlow<List<ImgItem>> = _cbImages
    val cbW = MutableStateFlow(720)
    val cbMaxH = MutableStateFlow(16000)
    val cbQ = MutableStateFlow(90)
    val cbBase = MutableStateFlow("GrooxOCR_gabung")

    // ---- pisah ----
    private val _spImages = MutableStateFlow<List<ImgItem>>(emptyList())
    val spImages: StateFlow<List<ImgItem>> = _spImages
    val spByCount = MutableStateFlow(true)
    val spParts = MutableStateFlow(3)
    val spSegH = MutableStateFlow(4000)
    val spQ = MutableStateFlow(90)
    val spBase = MutableStateFlow("GrooxOCR_pisah")

    // ---- watermark ----
    private val _wmImages = MutableStateFlow<List<ImgItem>>(emptyList())
    val wmImages: StateFlow<List<ImgItem>> = _wmImages
    val wmLogo = MutableStateFlow<Uri?>(null)
    val wmSource = MutableStateFlow(Watermark.Source.TEXT)
    val wmText = MutableStateFlow("GrooxOCR")
    val wmColorIdx = MutableStateFlow(0)
    val wmMode = MutableStateFlow(Watermark.Mode.SMART)
    val wmAnchor = MutableStateFlow(Watermark.Anchor.AUTO)
    val wmBlend = MutableStateFlow(Watermark.Blend.NORMAL)
    val wmCount = MutableStateFlow(2)
    val wmSize = MutableStateFlow(14)
    val wmOpacity = MutableStateFlow(70)
    val wmRotation = MutableStateFlow(0)
    val wmMargin = MutableStateFlow(32)
    val wmAvoid = MutableStateFlow(true)
    val wmQ = MutableStateFlow(90)
    val wmBase = MutableStateFlow("GrooxOCR_wm")
    private val _wmPreview = MutableStateFlow<Bitmap?>(null)
    val wmPreview: StateFlow<Bitmap?> = _wmPreview

    // ---- unwatermark (port setia remover v1.4.0) ----
    private val _uwImages = MutableStateFlow<List<ImgItem>>(emptyList())
    val uwImages: StateFlow<List<ImgItem>> = _uwImages
    val uwLogo = MutableStateFlow<Uri?>(null)
    val uwAnchor = MutableStateFlow(Unwatermark.Anchor9.TR)
    val uwOffX = MutableStateFlow(0f)
    val uwOffY = MutableStateFlow(0f)
    val uwAlpha = MutableStateFlow(100) // persen (100 = 1.0)
    val uwTrans = MutableStateFlow(3)
    val uwOpaque = MutableStateFlow(240)
    val uwSmooth = MutableStateFlow(false)
    val uwBright = MutableStateFlow(false)
    val uwSub = MutableStateFlow(false)
    val uwWholeR = MutableStateFlow(0)
    val uwBlend = MutableStateFlow(Unwatermark.PreviewBlend.NORMAL)
    val uwQ = MutableStateFlow(92)
    val uwBase = MutableStateFlow("GrooxOCR_unwm")
    private val _uwPreview = MutableStateFlow<Bitmap?>(null)
    val uwPreview: StateFlow<Bitmap?> = _uwPreview

    private var job: Job? = null

    // ---------- daftar gambar ----------

    fun addItems(ctx: Context, target: String, uris: List<Uri>) {
        val cur = current(target).toMutableList()
        for (u in uris) {
            if (cur.any { it.uri == u }) continue
            try {
                val info = ImageTiling.probe(ctx, u)
                cur.add(ImgItem(u, info.width, info.height))
            } catch (_: Exception) {}
        }
        set(target, cur)
    }

    fun removeItem(target: String, uri: Uri) =
        set(target, current(target).filterNot { it.uri == uri })

    fun moveItem(target: String, i: Int, dir: Int) {
        val cur = current(target).toMutableList()
        val j = i + dir
        if (i in cur.indices && j in cur.indices) {
            val t = cur[i]; cur[i] = cur[j]; cur[j] = t
            set(target, cur)
        }
    }

    fun moveCb(i: Int, d: Int) = moveItem("cb", i, d)
    fun moveSp(i: Int, d: Int) = moveItem("sp", i, d)
    fun moveWm(i: Int, d: Int) = moveItem("wm", i, d)

    fun clearItems(target: String) = set(target, emptyList())

    private fun current(t: String): List<ImgItem> = when (t) {
        "cb" -> _cbImages.value
        "sp" -> _spImages.value
        "uw" -> _uwImages.value
        else -> _wmImages.value
    }

    private fun set(t: String, v: List<ImgItem>) = when (t) {
        "cb" -> _cbImages.value = v
        "sp" -> _spImages.value = v
        "uw" -> _uwImages.value = v
        else -> _wmImages.value = v
    }

    fun moveUw(i: Int, d: Int) = moveItem("uw", i, d)

    fun backToIdle() { _ui.value = ToolUi.Idle }
    fun cancel() { job?.cancel(); _ui.value = ToolUi.Idle }

    // ---------- gabung ----------

    fun runCombine(ctx: Context) {
        val uris = _cbImages.value.map { it.uri }
        if (uris.isEmpty()) return fail("Pilih gambar dulu")
        job?.cancel()
        val app = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = ToolUi.Working("Menggabung…", 0, 1)
                val r = CombineImage.combine(app, uris, cbW.value, cbMaxH.value, cbQ.value, cbBase.value) { d, t ->
                    _ui.value = ToolUi.Working("Menggabung… ($d/$t)", d, t)
                }
                _ui.value = ToolUi.Done("combine", ToolResult(r.images, r.zip, "${r.width}×${r.totalH}px"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = ToolUi.Error(t.message ?: t.toString())
            }
        }
    }

    // ---------- pisah ----------

    fun runSplit(ctx: Context) {
        val uris = _spImages.value.map { it.uri }
        if (uris.isEmpty()) return fail("Pilih gambar dulu")
        job?.cancel()
        val app = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = ToolUi.Working("Memisah…", 0, 1)
                val r = SplitImage.split(
                    app, uris,
                    if (spByCount.value) spParts.value else null,
                    if (!spByCount.value) spSegH.value else null,
                    spQ.value, spBase.value,
                ) { d, t -> _ui.value = ToolUi.Working("Memisah… ($d/$t)", d, t) }
                _ui.value = ToolUi.Done("split", ToolResult(r.images, r.zip, "${r.images.size} potongan"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = ToolUi.Error(t.message ?: t.toString())
            }
        }
    }

    // ---------- watermark ----------

    fun wmOpts(): Watermark.Opts = Watermark.Opts(
        source = wmSource.value,
        text = wmText.value,
        textColor = Watermark.TEXT_COLORS[wmColorIdx.value % Watermark.TEXT_COLORS.size].second,
        logoUri = wmLogo.value,
        count = wmCount.value,
        sizePct = wmSize.value,
        opacity = wmOpacity.value,
        rotation = wmRotation.value,
        marginPx = wmMargin.value,
        mode = wmMode.value,
        anchor = wmAnchor.value,
        blend = wmBlend.value,
        avoidBubble = wmAvoid.value,
    )

    fun refreshPreview(ctx: Context) {
        val first = _wmImages.value.firstOrNull() ?: return fail("Pilih gambar dulu")
        job?.cancel()
        val app = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = ToolUi.Working("Pratinjau…", 0, 1)
                _wmPreview.value?.recycle()
                _wmPreview.value = Watermark.preview(app, first.uri, wmOpts())
                _ui.value = ToolUi.Idle
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = ToolUi.Error(t.message ?: t.toString())
            }
        }
    }

    fun runWatermark(ctx: Context) {
        val uris = _wmImages.value.map { it.uri }
        if (uris.isEmpty()) return fail("Pilih gambar dulu")
        job?.cancel()
        val app = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = ToolUi.Working("Watermark…", 0, uris.size)
                val r = Watermark.apply(app, uris, wmOpts(), wmQ.value, wmBase.value) { d, t ->
                    _ui.value = ToolUi.Working("Watermark… ($d/$t)", d, t)
                }
                _ui.value = ToolUi.Done("wm", ToolResult(r.images, r.zip, "${r.images.size} gambar"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = ToolUi.Error(t.message ?: t.toString())
            }
        }
    }

    // ---------- unwatermark ----------

    fun uwOpts(): Unwatermark.Opts = Unwatermark.Opts(
        anchor = uwAnchor.value,
        offX = uwOffX.value,
        offY = uwOffY.value,
        alphaAdjust = uwAlpha.value / 100f,
        transparencyThreshold = uwTrans.value,
        opaqueThreshold = uwOpaque.value,
        smoothEdges = uwSmooth.value,
        adjustBrightness = uwBright.value && uwSmooth.value,
        autoSubpixel = uwSub.value,
        wholePxRadius = uwWholeR.value,
    )

    /** Geser posisi (px full-res). Dipakai drag pratinjau & stepper. */
    fun shiftUw(dx: Float, dy: Float) {
        uwOffX.value = (uwOffX.value + dx).coerceIn(-2000f, 2000f)
        uwOffY.value = (uwOffY.value + dy).coerceIn(-2000f, 2000f)
    }

    fun nudgeUw(dx: Float, dy: Float) = shiftUw(dx, dy)

    fun refreshUwPreview(ctx: Context) {
        val first = _uwImages.value.firstOrNull() ?: return fail("Pilih gambar dulu")
        val logo = uwLogo.value ?: return fail("Pilih sampel watermark dulu")
        job?.cancel()
        val app = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = ToolUi.Working("Pratinjau…", 0, 1)
                _uwPreview.value?.recycle()
                _uwPreview.value = Unwatermark.preview(app, first.uri, logo, uwOpts(), uwBlend.value)
                _ui.value = ToolUi.Idle
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = ToolUi.Error(t.message ?: t.toString())
            }
        }
    }

    fun runUnwatermark(ctx: Context) {
        val uris = _uwImages.value.map { it.uri }
        if (uris.isEmpty()) return fail("Pilih gambar dulu")
        val logo = uwLogo.value ?: return fail("Pilih sampel watermark dulu")
        job?.cancel()
        val app = ctx.applicationContext
        job = viewModelScope.launch {
            try {
                _ui.value = ToolUi.Working("Unwatermark…", 0, uris.size)
                val r = Unwatermark.apply(app, uris, logo, uwOpts(), uwQ.value, uwBase.value) { d, t ->
                    _ui.value = ToolUi.Working("Unwatermark… ($d/$t)", d, t)
                }
                _ui.value = ToolUi.Done("uw", ToolResult(r.images, r.zip, "${r.images.size} gambar"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.value = ToolUi.Error(t.message ?: t.toString())
            }
        }
    }

    // ---------- rename + resolve ----------

    private fun baseOf(kind: String): String = when (kind) {
        "combine" -> cbBase.value
        "split" -> spBase.value
        "uw" -> uwBase.value
        else -> wmBase.value
    }

    /** Terapkan nama terbaru ke file-file + ZIP ulang bila berubah. */
    fun resolve(kind: String, r: ToolResult): ToolResult {
        val base = ZipKit.sanitize(baseOf(kind))
        val curBase = r.zip.nameWithoutExtension
        if (curBase == base && r.images.all { it.exists() } && r.zip.exists()) return r
        val named = ZipKit.ensureBaseNames(r.images.filter { it.exists() }, base)
        val zip = ZipKit.zip(named, File(named.firstOrNull()?.parentFile, "$base.zip"))
        r.images = named
        r.zip = zip
        // Segarkan state agar UI konsisten.
        (_ui.value as? ToolUi.Done)?.let {
            if (it.kind == kind) _ui.value = ToolUi.Done(kind, r)
        }
        return r
    }

    private fun fail(msg: String) {
        _ui.value = ToolUi.Error(msg)
    }

    override fun onCleared() {
        try { _wmPreview.value?.recycle() } catch (_: Exception) {}
        try { _uwPreview.value?.recycle() } catch (_: Exception) {}
        super.onCleared()
    }
}
