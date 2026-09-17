package com.groox.ocr.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groox.ocr.image.Watermark
import com.groox.ocr.image.Unwatermark
import com.groox.ocr.pdf.PdfShare
import com.groox.ocr.ui.viewmodel.ImageToolsViewModel
import com.groox.ocr.ui.viewmodel.ImgItem
import com.groox.ocr.ui.viewmodel.ToolResult
import com.groox.ocr.ui.viewmodel.ToolUi
import java.io.File

private val IMG_MIME = arrayOf("image/jpeg", "image/png", "image/webp")

private const val WM_JJAPTOON =
    "https://drive.google.com/drive/folders/1ZPAFYEdnS7V5eJeNP5AcrDDhJ1XjY6bk?usp=drive_link"
private const val WM_KOREA =
    "https://drive.google.com/drive/folders/17dWWAU0LzIUxjNn3p1JA5VqLZwSq7o_0?usp=drive_link"

private fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToolsScreen(vm: ImageToolsViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val cbImages by vm.cbImages.collectAsState()
    val spImages by vm.spImages.collectAsState()
    val wmImages by vm.wmImages.collectAsState()
    val uwImages by vm.uwImages.collectAsState()
    val spByCount by vm.spByCount.collectAsState()
    val wmSource by vm.wmSource.collectAsState()
    val wmMode by vm.wmMode.collectAsState()
    val wmColorIdx by vm.wmColorIdx.collectAsState()

    val pickCb = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addItems(ctx, "cb", uris) }
    val pickSp = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addItems(ctx, "sp", uris) }
    val pickWm = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addItems(ctx, "wm", uris) }
    val pickUw = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addItems(ctx, "uw", uris) }
    val pickUwLogo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.uwLogo.value = uri }
    val pickLogo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.wmLogo.value = uri }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ================= GABUNG =================
        item {
            Text("Gabung vertikal", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tumpuk gambar memanjang ke bawah. Lebar disamakan otomatis. " +
                    "Panjang & kualitas bisa diatur; output JPG + ZIP.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickCb.launch(IMG_MIME) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pilih gambar") }
                OutlinedButton(
                    onClick = { vm.clearItems("cb") },
                    modifier = Modifier.weight(1f),
                ) { Text("Bersihkan") }
            }
        }
        if (cbImages.isNotEmpty()) {
            item { Text("${cbImages.size} gambar", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(cbImages, key = { _, im -> "cb" + im.uri.toString() }) { i, im ->
                ImgRow(i, im, cbImages.size, { vm.moveCb(it, -1) }, { vm.moveCb(it, 1) }, { vm.removeItem("cb", it) })
            }
            item {
                Drop("Lebar hasil", listOf(480, 720, 1080, 1440), vm.cbW.collectAsState().value,
                    { "${it}px" }, { vm.cbW.value = it })
            }
            item {
                Drop("Panjang maks per file", listOf(8000, 16000, 24000, 30000), vm.cbMaxH.collectAsState().value,
                    { "${it}px" }, { vm.cbMaxH.value = it })
            }
            item { QSlider("Kualitas JPEG", vm.cbQ.collectAsState().value, 60..100) { vm.cbQ.value = it } }
            item {
                OutlinedTextField(
                    value = vm.cbBase.collectAsState().value,
                    onValueChange = { vm.cbBase.value = it.take(60) },
                    label = { Text("Nama output") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = { vm.runCombine(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Gabungkan")
                }
            }
        }

        item { HorizontalDivider(); Spacer(Modifier.height(4.dp)) }

        // ================= PISAH =================
        item {
            Text("Pisah vertikal", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Potong memanjang jadi beberapa bagian. Output JPG + ZIP.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickSp.launch(IMG_MIME) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pilih gambar") }
                OutlinedButton(
                    onClick = { vm.clearItems("sp") },
                    modifier = Modifier.weight(1f),
                ) { Text("Bersihkan") }
            }
        }
        if (spImages.isNotEmpty()) {
            item { Text("${spImages.size} gambar", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(spImages, key = { _, im -> "sp" + im.uri.toString() }) { i, im ->
                ImgRow(i, im, spImages.size, { vm.moveSp(it, -1) }, { vm.moveSp(it, 1) }, { vm.removeItem("sp", it) })
            }
            item {
                Drop("Mode pisah", listOf(true, false), vm.spByCount.collectAsState().value,
                    { if (it) "Jumlah bagian" else "Tinggi per bagian" }, { vm.spByCount.value = it })
            }
            if (spByCount) {
                item { QSlider("Jumlah bagian", vm.spParts.collectAsState().value, 2..10) { vm.spParts.value = it } }
            } else {
                item {
                    Drop("Tinggi per bagian", listOf(2000, 4000, 8000, 12000), vm.spSegH.collectAsState().value,
                        { "${it}px" }, { vm.spSegH.value = it })
                }
            }
            item { QSlider("Kualitas JPEG", vm.spQ.collectAsState().value, 60..100) { vm.spQ.value = it } }
            item {
                OutlinedTextField(
                    value = vm.spBase.collectAsState().value,
                    onValueChange = { vm.spBase.value = it.take(60) },
                    label = { Text("Nama output") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = { vm.runSplit(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pisahkan")
                }
            }
        }

        item { HorizontalDivider(); Spacer(Modifier.height(4.dp)) }

        // ================= WATERMARK =================
        item {
            Text("Smart Watermark", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Watermark teks/logo dengan penempatan otomatis yang menghindari " +
                    "area ramai & bubble dialog (analisis variansi). Output JPG + ZIP.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickWm.launch(IMG_MIME) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pilih gambar") }
                OutlinedButton(
                    onClick = { vm.clearItems("wm") },
                    modifier = Modifier.weight(1f),
                ) { Text("Bersihkan") }
            }
        }
        if (wmImages.isNotEmpty()) {
            item { Text("${wmImages.size} gambar", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(wmImages, key = { _, im -> "wm" + im.uri.toString() }) { i, im ->
                ImgRow(i, im, wmImages.size, { vm.moveWm(it, -1) }, { vm.moveWm(it, 1) }, { vm.removeItem("wm", it) })
            }
            item {
                Drop("Sumber", Watermark.Source.entries.toList(), vm.wmSource.collectAsState().value,
                    { it.label }, { vm.wmSource.value = it })
            }
            if (wmSource == Watermark.Source.TEXT) {
                item {
                    OutlinedTextField(
                        value = vm.wmText.collectAsState().value,
                        onValueChange = { vm.wmText.value = it.take(40) },
                        label = { Text("Teks watermark") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    val colors = Watermark.TEXT_COLORS
                    Drop("Warna teks", colors, colors[wmColorIdx % colors.size],
                        { it.first }, { c -> vm.wmColorIdx.value = colors.indexOf(c) })
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = { pickLogo.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (vm.wmLogo.collectAsState().value == null) "Pilih logo" else "Logo terpilih ✓ (tap ganti)")
                    }
                }
            }
            item {
                Drop("Penempatan", Watermark.Mode.entries.toList(), vm.wmMode.collectAsState().value,
                    { it.label }, { vm.wmMode.value = it })
            }
            item {
                Drop("Blend", Watermark.Blend.entries.toList(), vm.wmBlend.collectAsState().value,
                    { it.label }, { vm.wmBlend.value = it })
            }
            if (wmMode == Watermark.Mode.SMART) {
                item { QSlider("Jumlah per gambar", vm.wmCount.collectAsState().value, 1..6) { vm.wmCount.value = it } }
                item {
                    Drop("Posisi", Watermark.Anchor.entries.toList(), vm.wmAnchor.collectAsState().value,
                        { it.label }, { vm.wmAnchor.value = it })
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = vm.wmAvoid.collectAsState().value,
                            onCheckedChange = { vm.wmAvoid.value = it },
                        )
                        Text("Hindari bubble dialog")
                    }
                }
            }
            item { QSlider("Ukuran (% lebar)", vm.wmSize.collectAsState().value, 5..40) { vm.wmSize.value = it } }
            item { QSlider("Opasitas (%)", vm.wmOpacity.collectAsState().value, 10..100) { vm.wmOpacity.value = it } }
            item { QSlider("Rotasi (°)", vm.wmRotation.collectAsState().value, -45..45) { vm.wmRotation.value = it } }
            item { QSlider("Margin tepi (px)", vm.wmMargin.collectAsState().value, 0..120) { vm.wmMargin.value = it } }
            item { QSlider("Kualitas JPEG", vm.wmQ.collectAsState().value, 60..100) { vm.wmQ.value = it } }
            item {
                OutlinedTextField(
                    value = vm.wmBase.collectAsState().value,
                    onValueChange = { vm.wmBase.value = it.take(60) },
                    label = { Text("Nama output") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val prev = vm.wmPreview.collectAsState().value
                OutlinedButton(onClick = { vm.refreshPreview(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pratinjau penempatan")
                }
                if (prev != null) {
                    Image(
                        bitmap = prev.asImageBitmap(),
                        contentDescription = "Pratinjau watermark",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Button(onClick = { vm.runWatermark(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Terapkan ke ${wmImages.size} gambar")
                }
            }
        }

        // ================= UNWATERMARK =================
        item {
            Text("Unwatermark", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Hapus watermark transparan memakai SAMPEL watermark-nya " +
                    "(algoritma reverser v1.4.0, tidak diubah). Unduh sampel, " +
                    "posisikan tepat (geser pratinjau / tombol ±), lalu proses. " +
                    "Output JPG + ZIP.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openUrl(ctx, WM_JJAPTOON) },
                    modifier = Modifier.weight(1f),
                ) { Text("WM Jjaptoon") }
                OutlinedButton(
                    onClick = { openUrl(ctx, WM_KOREA) },
                    modifier = Modifier.weight(1f),
                ) { Text("WM Korea") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickUw.launch(IMG_MIME) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pilih gambar") }
                OutlinedButton(
                    onClick = { vm.clearItems("uw") },
                    modifier = Modifier.weight(1f),
                ) { Text("Bersihkan") }
            }
        }
        item {
            OutlinedButton(
                onClick = { pickUwLogo.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.uwLogo.collectAsState().value == null) "Pilih sampel watermark" else "Sampel ✓ (tap ganti)")
            }
        }
        if (uwImages.isNotEmpty()) {
            item { Text("${uwImages.size} gambar", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(uwImages, key = { _, im -> "uw" + im.uri.toString() }) { i, im ->
                ImgRow(i, im, uwImages.size, { vm.moveUw(it, -1) }, { vm.moveUw(it, 1) }, { vm.removeItem("uw", it) })
            }
            item {
                Drop("Anchor awal", Unwatermark.Anchor9.entries.toList(), vm.uwAnchor.collectAsState().value,
                    { it.label }, { vm.uwAnchor.value = it })
            }
            item {
                val ox = vm.uwOffX.collectAsState().value
                val oy = vm.uwOffY.collectAsState().value
                Text("Geser posisi: X=${ox.toInt()}px  Y=${oy.toInt()}px")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.nudgeUw(-10f, 0f) }) { Text("◀10") }
                    TextButton(onClick = { vm.nudgeUw(-1f, 0f) }) { Text("◀1") }
                    TextButton(onClick = { vm.nudgeUw(1f, 0f) }) { Text("1▶") }
                    TextButton(onClick = { vm.nudgeUw(10f, 0f) }) { Text("10▶") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.nudgeUw(0f, -10f) }) { Text("▲10") }
                    TextButton(onClick = { vm.nudgeUw(0f, -1f) }) { Text("▲1") }
                    TextButton(onClick = { vm.nudgeUw(0f, 1f) }) { Text("1▼") }
                    TextButton(onClick = { vm.nudgeUw(0f, 10f) }) { Text("10▼") }
                }
            }
            item {
                Drop("Pratinjau blend", Unwatermark.PreviewBlend.entries.toList(), vm.uwBlend.collectAsState().value,
                    { it.label }, { vm.uwBlend.value = it })
            }
            item {
                val prev = vm.uwPreview.collectAsState().value
                OutlinedButton(onClick = { vm.refreshUwPreview(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pratinjau posisi")
                }
                if (prev != null) {
                    val fullW = uwImages.firstOrNull()?.w ?: prev.width
                    val k = fullW.toFloat() / prev.width
                    Image(
                        bitmap = prev.asImageBitmap(),
                        contentDescription = "Pratinjau unwatermark (geser untuk reposisi)",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(prev) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    vm.shiftUw(drag.x * k, drag.y * k)
                                }
                            },
                    )
                    Text(
                        "Geser gambar pratinjau untuk reposisi (atau tombol ± di atas).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { QSlider("Alpha adjust (×100)", vm.uwAlpha.collectAsState().value, 50..150) { vm.uwAlpha.value = it } }
            item { QSlider("Ambang transparan", vm.uwTrans.collectAsState().value, 0..50) { vm.uwTrans.value = it } }
            item { QSlider("Ambang opak", vm.uwOpaque.collectAsState().value, 200..255) { vm.uwOpaque.value = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = vm.uwSmooth.collectAsState().value,
                        onCheckedChange = { vm.uwSmooth.value = it },
                    )
                    Text("Haluskan tepi")
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = vm.uwBright.collectAsState().value,
                        onCheckedChange = { vm.uwBright.value = it },
                    )
                    Text("Sesuaikan brightness tepi")
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = vm.uwSub.collectAsState().value,
                        onCheckedChange = { vm.uwSub.value = it },
                    )
                    Text("Perataan subpixel otomatis")
                }
            }
            item {
                Drop("Perataan whole-pixel", listOf(0, 2, 5, 10, 21), vm.uwWholeR.collectAsState().value,
                    { if (it == 0) "Mati" else "Radius ${it}px" }, { vm.uwWholeR.value = it })
            }
            item { QSlider("Kualitas JPEG", vm.uwQ.collectAsState().value, 60..100) { vm.uwQ.value = it } }
            item {
                OutlinedTextField(
                    value = vm.uwBase.collectAsState().value,
                    onValueChange = { vm.uwBase.value = it.take(60) },
                    label = { Text("Nama output") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = { vm.runUnwatermark(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Hapus watermark (${uwImages.size} gambar)")
                }
            }
        }

        item { HorizontalDivider(); Spacer(Modifier.height(4.dp)) }

        // ================= HASIL =================
        when (val s = ui) {
            is ToolUi.Working -> item {
                Card {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Column {
                            Text(s.stage)
                            if (s.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (s.done + 1).toFloat() / s.total.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Batal")
                }
            }
            is ToolUi.Done -> item {
                ToolResultCard(vm = vm, kind = s.kind, result = s.result, onDone = { vm.backToIdle() })
            }
            is ToolUi.Error -> item {
                Text("Gagal: ${s.message}", color = MaterialTheme.colorScheme.error)
            }
            ToolUi.Idle -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ImgRow(
    i: Int, im: ImgItem, n: Int,
    onUp: (Int) -> Unit, onDown: (Int) -> Unit, onDel: (android.net.Uri) -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${i + 1}. ${im.w}×${im.h}px", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onUp(i) }, enabled = i > 0) { Text("▲") }
            TextButton(onClick = { onDown(i) }, enabled = i < n - 1) { Text("▼") }
            TextButton(onClick = { onDel(im.uri) }) { Text("✕") }
        }
    }
}

@Composable
private fun ToolResultCard(vm: ImageToolsViewModel, kind: String, result: ToolResult, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var base by remember(result.zip.absolutePath) {
        mutableStateOf(result.zip.nameWithoutExtension)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (kind) {
                    "combine" -> "Gabung jadi ${result.images.size} file (${result.info})"
                    "split" -> "Pisah jadi ${result.images.size} potongan"
                    "uw" -> "Unwatermark: ${result.info}"
                    else -> "Watermark: ${result.info}"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text("${result.images.size} JPG • ZIP " + fmtB(result.zip.length()))
            OutlinedTextField(
                value = base,
                onValueChange = {
                    base = it.take(60)
                    when (kind) {
                        "combine" -> vm.cbBase.value = it.take(60)
                        "split" -> vm.spBase.value = it.take(60)
                        "uw" -> vm.uwBase.value = it.take(60)
                        else -> vm.wmBase.value = it.take(60)
                    }
                },
                label = { Text("Nama output (file + ZIP)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val r = remember(base) { vm.resolve(kind, result) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { PdfShare.shareZip(ctx, r.zip) }, modifier = Modifier.weight(1f)) {
                    Text("Bagikan ZIP")
                }
                OutlinedButton(
                    onClick = {
                        val uri = PdfShare.saveToDownloads(ctx, r.zip, r.zip.name)
                        Toast.makeText(
                            ctx,
                            if (uri != null) "Tersimpan: ${r.zip.name}" else "Butuh Android 10+ (pakai Bagikan)",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Simpan ZIP") }
            }
            if (r.images.size == 1) {
                OutlinedButton(
                    onClick = { PdfShare.shareImage(ctx, r.images[0]) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Bagikan gambar") }
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Kembali")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Drop(
    label: String, options: List<T>, selected: T,
    labelOf: (T) -> String, onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(labelOf(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
private fun QSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

private fun fmtB(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024)
}
