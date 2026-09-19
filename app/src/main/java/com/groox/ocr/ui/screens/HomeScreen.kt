package com.groox.ocr.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.groox.ocr.data.ModelManager
import com.groox.ocr.data.OcrParams
import com.groox.ocr.data.ReadingOrder
import com.groox.ocr.data.RecMode
import com.groox.ocr.ui.viewmodel.OcrUiState
import com.groox.ocr.ui.viewmodel.PickedOcrImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modelState: ModelManager.ModelState,
    params: OcrParams,
    uiState: OcrUiState,
    picked: List<PickedOcrImage>,
    onPickImage: () -> Unit,
    onPickImages: () -> Unit,
    onRemovePicked: (Uri) -> Unit,
    onClearPicked: () -> Unit,
    onInstallModels: () -> Unit,
    onRunBatch: () -> Unit,
    onRecMode: (RecMode) -> Unit,
    onReadingOrder: (ReadingOrder) -> Unit,
    onDetLongSide: (Int) -> Unit,
    onBoxThresh: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GrooxOCR — PP-OCR v5/v6 + AI dialog", style = MaterialTheme.typography.headlineSmall)
        Text(
            "OCR manhwa / manga / manhua. 5 model ter-bundel di APK (tanpa unduhan): " +
                "deteksi PP-OCRv6-small + rekognisi per bahasa — Korea (v5), English (v5), " +
                "auto 中文・日本語 (v6-small), Latin ES/VI/ID (v5). Bonus AI agnes-2.5-flash " +
                "menyusun hasil per dialog. Output per-bubble, JPG/PNG/WebP sampai 720×16000+.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // ---- Model status ----
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Model di APK", style = MaterialTheme.typography.titleSmall)
                Text("Deteksi PP-OCRv6-small: " + if (modelState.detReady) "siap" else "belum disalin")
                Text("Auto 中文・日本語 (v6-small): " + if (modelState.recV6Ready) "siap" else "belum disalin")
                Text("Korea (PP-OCRv5): " + if (modelState.recKoReady) "siap" else "belum disalin")
                Text("English (PP-OCRv5): " + if (modelState.recEnReady) "siap" else "belum disalin")
                Text("Latin ES/VI/ID (PP-OCRv5): " + if (modelState.recLatinReady) "siap" else "belum disalin")
                if (modelState.installing != null) {
                    LinearProgressIndicator(
                        progress = { modelState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Menyalin ${modelState.installing}… ${(modelState.progress * 100).toInt()}%")
                }
                if (modelState.error != null) {
                    Text("Error: ${modelState.error}", color = MaterialTheme.colorScheme.error)
                }
                if (!modelState.primaryReady && modelState.installing == null) {
                    Button(onClick = onInstallModels, modifier = Modifier.fillMaxWidth()) {
                        Text("Salin model dari APK")
                    }
                }
            }
        }

        // ---- Images (multi) ----
        val working = uiState as? OcrUiState.Working
        val error = uiState as? OcrUiState.Error
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                Text("+ 1 gambar")
            }
            OutlinedButton(onClick = onPickImages, modifier = Modifier.weight(1f)) {
                Text("+ banyak gambar")
            }
        }
        if (picked.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${picked.size} gambar terpilih",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onClearPicked) {
                    Text("Hapus semua")
                }
            }
            picked.forEachIndexed { i, p ->
                Card {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${i + 1}. ${p.info.width} × ${p.info.height}px",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${p.info.mime ?: "?"} • ${formatBytes(p.info.byteSize)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = { onRemovePicked(p.uri) }) {
                            Text("✕")
                        }
                    }
                }
            }
        } else {
            Text(
                "Belum ada gambar. Pilih 1 atau banyak gambar (JPG/PNG/WebP) — " +
                    "OCR berjalan berurutan per gambar.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (working != null) {
            Card {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Column {
                        Text(working.stage)
                        if (working.total > 0) {
                            LinearProgressIndicator(
                                progress = { (working.done + 1).toFloat() / working.total.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Batal")
            }
        }
        if (error != null) {
            Text("Gagal: ${error.message}", color = MaterialTheme.colorScheme.error)
        }

        // ---- OCR settings ----
        Text("Bahasa & bacaan", style = MaterialTheme.typography.titleSmall)
        ModeDropdown(
            label = "Mode bahasa",
            options = RecMode.entries.toList(),
            selected = params.recMode,
            labelOf = { it.label },
            onSelect = onRecMode,
        )
        ModeDropdown(
            label = "Urutan baca",
            options = ReadingOrder.entries.toList(),
            selected = params.readingOrder,
            labelOf = { it.label },
            onSelect = onReadingOrder,
        )
        Text("Deteksi long-side: ${params.detLongSide}px (maks model 4000px)")
        ModeDropdown(
            label = "Long-side deteksi",
            options = listOf(960, 1280, 1536, 2048, 2560, 3200, 4000),
            selected = params.detLongSide,
            labelOf = { "$it px" },
            onSelect = onDetLongSide,
        )
        if (params.detLongSide >= 2048) {
            Text(
                "≥2048px berat di memori (otomatis dibatasi ~4 juta px). " +
                    "Bisa gagal di HP kentang — turunkan bila crash.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Box threshold: ${"%.2f".format(params.boxThresh)}")
        Slider(
            value = params.boxThresh,
            onValueChange = onBoxThresh,
            valueRange = 0.3f..0.7f,
            steps = 8,
        )

        if (picked.isNotEmpty() && working == null) {
            Button(
                onClick = onRunBatch,
                enabled = modelState.primaryReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Jalankan OCR ${picked.size} gambar (per-bubble)")
            }
            if (!modelState.primaryReady) {
                Text("Salin model dulu sebelum OCR.")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatBytes(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ModeDropdown(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
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
                DropdownMenuItem(
                    text = { Text(labelOf(o)) },
                    onClick = { onSelect(o); open = false },
                )
            }
        }
    }
}
