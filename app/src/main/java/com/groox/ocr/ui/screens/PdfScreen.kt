package com.groox.ocr.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groox.ocr.pdf.ImageToPdf
import com.groox.ocr.pdf.PdfCompressor
import com.groox.ocr.pdf.PdfShare
import com.groox.ocr.ui.viewmodel.PdfUiState
import com.groox.ocr.ui.viewmodel.PdfViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(vm: PdfViewModel) {
    val ctx = LocalContext.current
    val images by vm.images.collectAsState()
    val ui by vm.ui.collectAsState()
    val quality by vm.quality.collectAsState()
    val pageWidth by vm.pageWidth.collectAsState()
    val level by vm.level.collectAsState()

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addImages(ctx, uris) }
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.compress(ctx, uri) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Gambar → PDF", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tiap gambar jadi 1 halaman penuh selebar kanvas (fit-width), " +
                    "JPEG asli ditempel tanpa re-encode pada mode Original — tetap tajam. " +
                    "Strip 720×16000+ otomatis dipecah jadi halaman berurutan yang bisa di-scroll.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickImages.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pilih gambar") }
                OutlinedButton(onClick = { vm.clear() }, modifier = Modifier.weight(1f)) {
                    Text("Bersihkan")
                }
            }
        }
        if (images.isNotEmpty()) {
            item {
                Text(
                    "${images.size} gambar terpilih (urutan = urutan halaman)",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            itemsIndexed(images, key = { _, im -> im.uri.toString() }) { i, im ->
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
                            Text(fmtBytes(im.bytes), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.moveUp(i) }, enabled = i > 0) { Text("▲") }
                        TextButton(onClick = { vm.moveDown(i) }, enabled = i < images.size - 1) { Text("▼") }
                        TextButton(onClick = { vm.removeAt(i) }) { Text("✕") }
                    }
                }
            }
            item {
                Drop(
                    label = "Kualitas",
                    options = ImageToPdf.Quality.entries.toList(),
                    selected = quality,
                    labelOf = { "${it.label} (q${it.jpegQ}${if (it.maxW == Int.MAX_VALUE) "" else ", ≤${it.maxW}px"})" },
                    onSelect = { vm.setQuality(it) },
                )
            }
            item {
                Drop(
                    label = "Lebar halaman",
                    options = ImageToPdf.PageWidth.entries.toList(),
                    selected = pageWidth,
                    labelOf = { it.label },
                    onSelect = { vm.setPageWidth(it) },
                )
            }
            item {
                Button(
                    onClick = { vm.convert(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Buat PDF (${images.size} gambar)") }
            }
        }

        item { HorizontalDivider(); Spacer(Modifier.height(4.dp)) }

        item {
            Text("Kompres PDF", style = MaterialTheme.typography.headlineSmall)
            Text(
                "PDF di-render ulang per halaman lalu dibangun ulang sebagai PDF JPEG. " +
                    "Efektif mengecilkan PDF scan/komik yang bengkak. Full offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Drop(
                label = "Level kompres",
                options = PdfCompressor.Level.entries.toList(),
                selected = level,
                labelOf = { it.label },
                onSelect = { vm.setLevel(it) },
            )
        }
        item {
            OutlinedButton(
                onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pilih PDF untuk dikompres") }
        }

        when (val s = ui) {
            is PdfUiState.Working -> item {
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
            is PdfUiState.ConvertDone -> item {
                ResultCard(
                    title = "PDF jadi: ${s.result.pages} halaman dari ${s.result.images} gambar",
                    size = fmtBytes(s.result.bytes),
                    file = s.result.file,
                    onDone = { vm.backToList() },
                )
            }
            is PdfUiState.CompressDone -> item {
                val pct = (s.result.ratio * 100).toInt()
                ResultCard(
                    title = "Kompres selesai: ${s.result.pages} halaman",
                    size = "${fmtBytes(s.result.inBytes)} → ${fmtBytes(s.result.outBytes)} ($pct%)",
                    file = s.result.file,
                    onDone = { vm.backToList() },
                )
            }
            is PdfUiState.Error -> item {
                Text("Gagal: ${s.message}", color = MaterialTheme.colorScheme.error)
            }
            PdfUiState.Idle -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResultCard(title: String, size: String, file: File, onDone: () -> Unit) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(size)
            Text(file.name, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { PdfShare.sharePdf(ctx, file) },
                    modifier = Modifier.weight(1f),
                ) { Text("Bagikan") }
                OutlinedButton(
                    onClick = {
                        val uri = PdfShare.saveToDownloads(ctx, file, file.name)
                        Toast.makeText(
                            ctx,
                            if (uri != null) "Tersimpan di Download/GrooxOCR" else "Butuh Android 10+ (pakai Bagikan)",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Simpan") }
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
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        androidx.compose.material3.OutlinedTextField(
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

private fun fmtBytes(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024)
}
