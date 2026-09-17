package com.groox.ocr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groox.ocr.mt.Translator
import com.groox.ocr.ui.viewmodel.TranslateUi
import com.groox.ocr.ui.viewmodel.TranslateViewModel

/**
 * Terjemahan offline (MarianMT INT8): ketik/paste manual atau dari file TXT.
 * KO→EN, EN→ID, KO→ID (rantai KO→EN→ID).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(vm: TranslateViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val input by vm.input.collectAsState()
    val dir by vm.dir.collectAsState()

    val pickTxt = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.loadTxt(ctx, uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terjemahan (offline)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Model MarianMT INT8 di HP (tanpa internet). Cocok untuk hasil OCR " +
                "bubble: paste teks atau ambil file TXT.",
            style = MaterialTheme.typography.bodyMedium,
        )

        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
            OutlinedTextField(
                value = dir.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Arah") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Translator.Direction.entries.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d.label) },
                        onClick = { vm.setDir(d); open = false },
                    )
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { vm.setInput(it) },
            label = { Text("Teks sumber (ketik / paste)") },
            placeholder = { Text("예: 오늘 날씨가 좋네요") },
            minLines = 5,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { pickTxt.launch(arrayOf("text/plain")) },
                modifier = Modifier.weight(1f),
            ) { Text("Dari file TXT") }
            OutlinedButton(
                onClick = { vm.clear() },
                modifier = Modifier.weight(1f),
            ) { Text("Bersihkan") }
        }

        when (val s = ui) {
            is TranslateUi.Working -> {
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
            is TranslateUi.Done -> {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hasil (${dir.label})", style = MaterialTheme.typography.titleSmall)
                        Text(s.output)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("GrooxOCR", s.output))
                            Toast.makeText(ctx, "Hasil disalin", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Salin") }
                    OutlinedButton(
                        onClick = { shareText(ctx, s.output) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Bagikan") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { saveTxt(ctx, s.output) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Simpan TXT") }
                    OutlinedButton(
                        onClick = { vm.backToEdit() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Edit lagi") }
                }
            }
            is TranslateUi.Error -> {
                Text("Gagal: ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.translate() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Terjemahkan")
                }
            }
            TranslateUi.Idle -> {
                Button(onClick = { vm.translate() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Terjemahkan")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun shareText(ctx: Context, s: String) {
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, s)
            },
            "Bagikan",
        )
    )
}

private fun saveTxt(ctx: Context, s: String) {
    try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(ctx, "Butuh Android 10+", Toast.LENGTH_LONG).show()
            return
        }
        val name = "GrooxOCR_terjemah_${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/GrooxOCR",
            )
        }
        val uri = ctx.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw RuntimeException("MediaStore gagal")
        ctx.contentResolver.openOutputStream(uri)?.use {
            it.write(s.toByteArray(Charsets.UTF_8))
        }
        Toast.makeText(ctx, "Tersimpan: $name", Toast.LENGTH_LONG).show()
    } catch (t: Throwable) {
        Toast.makeText(ctx, "Gagal simpan: ${t.message}", Toast.LENGTH_LONG).show()
    }
}
