package com.groox.ocr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.groox.ocr.data.AgnesClient
import com.groox.ocr.engine.BubbleGrouper
import com.groox.ocr.engine.OcrEngine
import com.groox.ocr.ui.components.OcrOverlay
import com.groox.ocr.ui.viewmodel.AiUiState
import com.groox.ocr.ui.viewmodel.BatchItem
import com.groox.ocr.util.ExportUtils
import com.groox.ocr.util.ExportUtils.BubblePrefix

/**
 * Hasil OCR batch:
 * - tiap bubble = 1 baris, dengan awalan pilihan user (-, •, >, nomor),
 * - tiap bubble bisa dihapus (ada tombol kembalikan),
 * - tombol "Salin semua" menyalin SEKALIGUS semua teks yang tampil,
 * - tombol "AI: susun per dialog" mengirim teks ke agnes-2.5-flash yang
 *   SELALU mengembalikan hasil per dialog walau sumbernya bukan bubble/narasi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    items: List<BatchItem>,
    aiState: AiUiState,
    onBack: () -> Unit,
    onCopyAll: (String) -> Unit,
    onAiRefine: () -> Unit,
) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    var prefix by remember { mutableStateOf(BubblePrefix.NONE) }
    var customPrefix by remember { mutableStateOf("- ") }
    // (indexGambar, idBubble) yang disembunyikan user.
    var hidden by remember(items) { mutableStateOf(setOf<Pair<Int, Int>>()) }

    val safeIdx = idx.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val cur = items.getOrNull(safeIdx)
    // Bubble yang tampil per gambar (hasil hapus user).
    val visible: List<List<BubbleGrouper.Bubble>> = remember(items, hidden) {
        items.mapIndexed { i, it ->
            it.result.bubbles.filterNot { b -> hidden.contains(i to b.id) }
        }
    }
    val curVisible = visible.getOrElse(safeIdx) { emptyList() }
    val allTxt = remember(visible, prefix, customPrefix) {
        ExportUtils.batchBubblesToTxt(visible, prefix, customPrefix)
    }
    val hiddenHere = hidden.count { it.first == safeIdx }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Kembali")
                }
                Button(
                    onClick = { onCopyAll(allTxt) },
                    modifier = Modifier.weight(1f),
                    enabled = allTxt.isNotBlank(),
                ) {
                    Text("Salin semua")
                }
            }
        }

        // ---- AI: susun per dialog (agnes-2.5-flash) ----
        item {
            Button(
                onClick = onAiRefine,
                modifier = Modifier.fillMaxWidth(),
                enabled = allTxt.isNotBlank() && aiState !is AiUiState.Working,
            ) {
                Text("✨ AI: susun per dialog (${AgnesClient.MODEL})")
            }
        }
        when (aiState) {
            is AiUiState.Working -> item {
                Card {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("AI sedang menyusun dialog… (butuh internet)")
                    }
                }
            }
            is AiUiState.Error -> item {
                Text(
                    "AI gagal: ${aiState.message}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is AiUiState.Done -> item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Hasil AI per dialog — ${aiState.model} • ${aiState.elapsedMs} ms",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(aiState.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { copyText(ctx, aiState.text) }) {
                                Text("Salin hasil AI")
                            }
                            TextButton(
                                onClick = {
                                    ctx.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, aiState.text)
                                            },
                                            "Bagikan",
                                        )
                                    )
                                },
                            ) { Text("Bagikan") }
                        }
                    }
                }
            }
            AiUiState.Idle -> Unit
        }

        if (items.size > 1) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { idx = (safeIdx - 1).coerceAtLeast(0) },
                        enabled = safeIdx > 0,
                    ) { Text("‹ Sblm") }
                    Text(
                        "Gambar ${safeIdx + 1}/${items.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { idx = (safeIdx + 1).coerceAtMost(items.size - 1) },
                        enabled = safeIdx < items.size - 1,
                    ) { Text("Berikut ›") }
                }
            }
        }
        // ---- Format awalan ----
        item {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
                OutlinedTextField(
                    value = prefix.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Awalan tiap baris") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    BubblePrefix.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = { prefix = p; open = false },
                        )
                    }
                }
            }
        }
        if (prefix == BubblePrefix.CUSTOM) {
            item {
                OutlinedTextField(
                    value = customPrefix,
                    onValueChange = { customPrefix = it.take(16) },
                    label = { Text("Awalan custom (mis. “- ”, “>> ”, “[TEKS] ”)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (cur != null) {
            item { ImageResultCard(item = cur) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, allTxt)
                                    },
                                    "Bagikan",
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Bagikan TXT") }
                    OutlinedButton(
                        onClick = {
                            val results = items.map { it.result }
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, ExportUtils.batchToJson(results))
                                    },
                                    "Bagikan",
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Bagikan JSON") }
                }
            }
            if (hiddenHere > 0) {
                item {
                    OutlinedButton(
                        onClick = {
                            hidden = hidden.filterNot { it.first == safeIdx }.toSet()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Kembalikan $hiddenHere teks yang dihapus") }
                }
            }
            items(curVisible, key = { it.id }) { b ->
                val number = curVisible.indexOf(b) + 1
                BubbleCard(
                    text = prefix.apply(b.text, number, customPrefix),
                    score = b.avgScore,
                    lines = b.lines.size,
                    onCopy = { copyText(ctx, prefix.apply(b.text, number, customPrefix)) },
                    onDelete = { hidden = hidden + (safeIdx to b.id) },
                )
            }
            if (curVisible.isEmpty()) {
                item {
                    Text(
                        if (cur.result.bubbles.isEmpty())
                            "Tidak ada teks terdeteksi. Coba turunkan box threshold ke 0.35."
                        else "Semua teks gambar ini dihapus. Pakai tombol kembalikan di atas."
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageResultCard(item: BatchItem) {
    val result: OcrEngine.OcrResult = item.result
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${result.imageWidth}×${result.imageHeight} • " +
                    "${result.lines.size} baris → ${result.bubbles.size} bubble • " +
                    "${result.elapsedMs} ms",
                style = MaterialTheme.typography.bodySmall,
            )
            // Preview: decode manual dengan downsample (strip 720×16000+ melebihi
            // batas tekstur GPU sehingga loader async biasa menampilkan LAYAR KOSONG).
            // Tinggi Box tetap 420dp; overlay pakai matematika Fit yang sama.
            val appCtx = LocalContext.current
            val preview by produceState<Bitmap?>(initialValue = null, item.uri) {
                value = withContext(Dispatchers.IO) { decodePreviewBitmap(appCtx, item.uri) }
            }
            // Preview FIT-LEBAR kolom + scrollable vertikal (strip 16000px+).
            // Box dalam memakai aspectRatio persis rasio gambar -> matematika
            // Fit overlay tetap akurat (tanpa letterbox, dx=dy=0).
            val previewScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .verticalScroll(previewScroll)
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val pb = preview
                if (pb != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pb.width.toFloat() / pb.height.toFloat()),
                    ) {
                        Image(
                            bitmap = pb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                        OcrOverlay(
                            boxes = result.bubbles.map { it.rect },
                            imageWidth = result.imageWidth,
                            imageHeight = result.imageHeight,
                        )
                    }
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun BubbleCard(
    text: String,
    score: Float,
    lines: Int,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onCopy,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Skor ${"%.2f".format(score)} • $lines baris",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text("Salin") }
                TextButton(onClick = onDelete) { Text("Hapus") }
            }
        }
    }
}

/** Decode preview yang aman memori: maks ~1080px lebar / ~6000px tinggi (RGB565 ~13MB). */
private fun decodePreviewBitmap(ctx: Context, uri: android.net.Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1080 || bounds.outHeight / sample > 6000) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (t: Throwable) {
        null
    }
}

private fun copyText(ctx: Context, s: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GrooxOCR", s))
}
