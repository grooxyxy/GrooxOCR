package com.groox.ocr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.groox.ocr.engine.BubbleGrouper
import com.groox.ocr.engine.OcrEngine
import com.groox.ocr.ui.components.OcrOverlay
import com.groox.ocr.util.ExportUtils

/**
 * Hasil OCR: preview + overlay bubble, daftar bubble (tap salin),
 * ekspor TXT/JSON per-bubble, bagikan.
 */
@Composable
fun ResultScreen(
    uri: Uri,
    result: OcrEngine.OcrResult,
    onBack: () -> Unit,
    onCopyAll: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val allTxt = remember(result) { ExportUtils.bubblesToTxt(result.bubbles) }
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
                ) {
                    Text("Salin semua")
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${result.imageWidth}×${result.imageHeight} • " +
                            "${result.lines.size} baris → ${result.bubbles.size} bubble • " +
                            "${result.elapsedMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Preview fit-width; overlay diskala proporsional.
                    val aspect = result.imageWidth.toFloat() / result.imageHeight.coerceAtLeast(1)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect.coerceIn(0.05f, 3f)),
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        OcrOverlay(
                            boxes = result.bubbles.map { it.rect },
                            imageWidth = result.imageWidth,
                            imageHeight = result.imageHeight,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { shareText(ctx, allTxt) },
                    modifier = Modifier.weight(1f),
                ) { Text("Bagikan TXT") }
                OutlinedButton(
                    onClick = { shareText(ctx, ExportUtils.resultToJson(result)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Bagikan JSON") }
            }
        }
        items(result.bubbles, key = { it.id }) { b ->
            BubbleCard(bubble = b)
        }
        if (result.bubbles.isEmpty()) {
            item { Text("Tidak ada teks terdeteksi. Coba turunkan box threshold ke 0.35.") }
        }
    }
}

@Composable
private fun BubbleCard(bubble: BubbleGrouper.Bubble) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = { copyText(ctx, bubble.text) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Bubble ${bubble.id + 1} • ${"%.2f".format(bubble.avgScore)}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(bubble.text)
            Text(
                "Tap untuk salin • ${bubble.lines.size} baris",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun copyText(ctx: Context, s: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GrooxOCR", s))
}

private fun shareText(ctx: Context, s: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, s)
    }
    ctx.startActivity(Intent.createChooser(i, "Bagikan"))
}
