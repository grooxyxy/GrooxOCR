package com.groox.ocr.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import coil.compose.AsyncImage
import com.groox.ocr.data.RecMode
import com.groox.ocr.image.CleanEngine
import com.groox.ocr.pdf.PdfShare
import com.groox.ocr.ui.components.OcrOverlay
import com.groox.ocr.ui.viewmodel.CleanUi
import com.groox.ocr.ui.viewmodel.CleanViewModel

private val IMG_MIME = arrayOf("image/jpeg", "image/png", "image/webp")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanScreen(vm: CleanViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val images by vm.images.collectAsState()
    val recMode by vm.recMode.collectAsState()
    val method by vm.method.collectAsState()

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addImages(ctx, uris) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Bersih (hapus teks)", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Upload 1/banyak gambar → deteksi PP-OCR → pilih kata → hapus. " +
                    "Solid diisi warna sekitar, gradasi via difusi (tanpa model), " +
                    "tekstur via MiGAN. Output ZIP = jumlah gambar + pratinjau.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pick.launch(IMG_MIME) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pilih gambar") }
                OutlinedButton(
                    onClick = { vm.clearImages() },
                    modifier = Modifier.weight(1f),
                ) { Text("Bersihkan") }
            }
        }
        if (images.isNotEmpty()) {
            item { Text("${images.size} gambar", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(images, key = { _, im -> "cl" + im.uri.toString() }) { i, im ->
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
                        TextButton(onClick = { vm.removeImage(im.uri) }) { Text("✕") }
                    }
                }
            }
            item {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
                    OutlinedTextField(
                        value = recMode.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Bahasa deteksi") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        RecMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                onClick = { vm.recMode.value = m; open = false },
                            )
                        }
                    }
                }
            }
            item {
                Button(onClick = { vm.runDetect() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Deteksi teks")
                }
            }
        }

        when (val s = ui) {
            is CleanUi.Detecting -> item {
                WorkCard("Deteksi ${s.done}/${s.total}", s.done, s.total) { vm.cancel() }
            }
            is CleanUi.WordsReady -> {
                item {
                    var mOpen by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = mOpen, onExpandedChange = { mOpen = !mOpen }) {
                        OutlinedTextField(
                            value = method.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Metode hapus") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(mOpen) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = mOpen, onDismissRequest = { mOpen = false }) {
                            CleanEngine.Method.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = { vm.method.value = m; mOpen = false },
                                )
                            }
                        }
                    }
                }
                item {
                    val q = vm.jpegQ.collectAsState().value
                    Text("Kualitas JPEG: $q")
                    Slider(
                        value = q.toFloat(),
                        onValueChange = { vm.jpegQ.value = it.toInt().coerceIn(60, 100) },
                        valueRange = 60f..100f,
                    )
                }
                s.items.forEachIndexed { idx, det ->
                    item(key = "prev$idx") {
                        Card {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Gambar ${idx + 1}: ${det.words.size} kata terdeteksi",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val aspect = det.w.toFloat() / det.h.coerceAtLeast(1)
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspect.coerceIn(0.05f, 3f)),
                                ) {
                                    AsyncImage(
                                        model = det.uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    OcrOverlay(
                                        boxes = det.words.filter { it.selected }.map { it.rect },
                                        imageWidth = det.w,
                                        imageHeight = det.h,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { vm.selectAll(idx, true) }) { Text("Pilih semua") }
                                    TextButton(onClick = { vm.selectAll(idx, false) }) { Text("Hapus centang") }
                                }
                            }
                        }
                    }
                    itemsIndexed(det.words, key = { _, w -> "w$idx-${w.id}" }) { _, w ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (w.selected) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            ),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = w.selected,
                                    onCheckedChange = { vm.toggleWord(idx, w.id) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(w.text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "metode: ${w.kind.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = vm.base.collectAsState().value,
                        onValueChange = { vm.base.value = it.take(60) },
                        label = { Text("Nama output") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.runPreview() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Pratinjau") }
                        Button(
                            onClick = { vm.runProcess() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Proses ZIP") }
                    }
                }
            }
            is CleanUi.Cleaning -> item {
                WorkCard(s.stage + " ${s.done}/${s.total}", s.done, s.total) { vm.cancel() }
            }
            is CleanUi.PreviewReady -> {
                s.cleaned.forEachIndexed { idx, c ->
                    item(key = "cpv$idx") {
                        var showAfter by remember { mutableStateOf(true) }
                        Card {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Pratinjau gambar ${idx + 1} (${if (showAfter) "sesudah" else "sebelum"})",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val aspect = c.w.toFloat() / c.h.coerceAtLeast(1)
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspect.coerceIn(0.05f, 3f)),
                                ) {
                                    if (showAfter) {
                                        Image(
                                            bitmap = c.bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        AsyncImage(
                                            model = c.uri,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { showAfter = !showAfter },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (showAfter) "Lihat sebelum" else "Lihat sesudah") }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.backToWords() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Ubah pilihan") }
                        Button(
                            onClick = { vm.runProcess() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Proses ZIP") }
                    }
                }
            }
            is CleanUi.Done -> item {
                var name by remember(s.result.zip.absolutePath) {
                    mutableStateOf(s.result.zip.nameWithoutExtension)
                }
                val r = remember(name) { vm.resolve(s.result) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Bersih: ${r.info}", style = MaterialTheme.typography.titleSmall)
                        Text("${r.images.size} JPG • ZIP " + fmtB(r.zip.length()))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(60); vm.base.value = it.take(60) },
                            label = { Text("Nama output") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    ctx.startActivity(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "application/zip"
                                                putExtra(
                                                    android.content.Intent.EXTRA_STREAM,
                                                    com.groox.ocr.pdf.PdfShare.contentUri(ctx, r.zip),
                                                )
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            "Bagikan ZIP",
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Bagikan ZIP") }
                            OutlinedButton(
                                onClick = {
                                    val uri = com.groox.ocr.pdf.PdfShare.saveToDownloads(ctx, r.zip, r.zip.name)
                                    Toast.makeText(
                                        ctx,
                                        if (uri != null) "Tersimpan: ${r.zip.name}" else "Butuh Android 10+",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Simpan ZIP") }
                        }
                        if (r.images.size == 1) {
                            OutlinedButton(
                                onClick = { com.groox.ocr.pdf.PdfShare.shareImage(ctx, r.images[0]) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Bagikan gambar") }
                        }
                        OutlinedButton(
                            onClick = { vm.backToWords() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Kembali") }
                    }
                }
            }
            is CleanUi.Error -> item {
                Text("Gagal: ${s.message}", color = MaterialTheme.colorScheme.error)
            }
            CleanUi.Idle -> {}
        }
        item { HorizontalDivider(); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun WorkCard(stage: String, done: Int, total: Int, onCancel: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Column {
                    Text(stage)
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (done + 1).toFloat() / total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Batal")
            }
        }
    }
}

private fun fmtB(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024)
}
