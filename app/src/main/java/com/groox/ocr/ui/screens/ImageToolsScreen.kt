package com.groox.ocr.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groox.ocr.image.Watermark
import com.groox.ocr.pdf.PdfShare
import com.groox.ocr.ui.viewmodel.ImageToolsViewModel
import com.groox.ocr.ui.viewmodel.ImgItem
import com.groox.ocr.ui.viewmodel.ToolResult
import com.groox.ocr.ui.viewmodel.ToolUi
import java.io.File

private val IMG_MIME = arrayOf("image/jpeg", "image/png", "image/webp")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToolsScreen(vm: ImageToolsViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val cbImages by vm.cbImages.collectAsState()
    val spImages by vm.spImages.collectAsState()
    val wmImages by vm.wmImages.collectAsState()
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
