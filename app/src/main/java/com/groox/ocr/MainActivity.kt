package com.groox.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.ui.screens.HomeScreen
import com.groox.ocr.ui.screens.ResultScreen
import com.groox.ocr.ui.theme.GrooxTheme
import com.groox.ocr.ui.viewmodel.OcrUiState
import com.groox.ocr.ui.viewmodel.OcrViewModel

class MainActivity : ComponentActivity() {

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GrooxOcrApp
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OcrViewModel(app.modelManager, app.ocrEngine) as T
            }
        }
        setContent {
            GrooxTheme {
                val vm: OcrViewModel = viewModel(factory = factory)
                val uiState by vm.ui.collectAsState()
                val modelState by vm.modelState.collectAsState()
                val params by vm.params.collectAsState()

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {}
                        try {
                            val info = ImageTiling.probe(this, uri)
                            vm.pickImage(uri, info)
                        } catch (t: Throwable) {
                            Toast.makeText(this, t.message ?: "Gambar tidak valid", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                when (val s = uiState) {
                    is OcrUiState.Done -> ResultScreen(
                        uri = s.uri,
                        result = s.result,
                        onBack = {
                            try {
                                val info = ImageTiling.probe(this, s.uri)
                                vm.backToImage(s.uri, info)
                            } catch (_: Exception) { vm.cancel() }
                        },
                        onCopyAll = { txt ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("GrooxOCR", txt))
                            Toast.makeText(this, "Disalin (${s.result.bubbles.size} bubble)", Toast.LENGTH_SHORT).show()
                        },
                    )
                    else -> HomeScreen(
                        modelState = modelState,
                        params = params,
                        uiState = s,
                        onPickImage = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                        onInstallModels = { vm.installModels(params.recMode != com.groox.ocr.data.RecMode.V6_ONLY) },
                        onRun = { vm.runOcr(it) },
                        onRecMode = { vm.setRecMode(it) },
                        onReadingOrder = { vm.setReadingOrder(it) },
                        onDetLongSide = { vm.setDetLongSide(it) },
                        onBoxThresh = { vm.setBoxThresh(it) },
                        onCancel = { vm.cancel() },
                    )
                }
            }
        }

        // Handle VIEW intent (open image from gallery).
        intent?.data?.let { uri ->
            try {
                val info = ImageTiling.probe(this, uri)
                val app2 = application as GrooxOcrApp
                // VM created in composition; store pending via intent extra fallback:
                // simplest: probe only; user taps pick if needed. But try direct:
            } catch (_: Exception) {}
        }
    }
}
