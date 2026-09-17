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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groox.ocr.data.ImageTiling
import com.groox.ocr.ui.screens.HomeScreen
import com.groox.ocr.ui.screens.PdfScreen
import com.groox.ocr.ui.screens.ResultScreen
import com.groox.ocr.ui.theme.GrooxTheme
import com.groox.ocr.ui.viewmodel.OcrUiState
import com.groox.ocr.ui.viewmodel.OcrViewModel
import com.groox.ocr.ui.viewmodel.PdfViewModel

class MainActivity : ComponentActivity() {

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GrooxOcrApp
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when (modelClass) {
                    OcrViewModel::class.java ->
                        OcrViewModel(app.modelManager, app.ocrEngine) as T
                    PdfViewModel::class.java -> PdfViewModel() as T
                    else -> throw IllegalArgumentException(modelClass.name)
                }
            }
        }
        setContent {
            GrooxTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold { inner ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(inner),
                    ) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("OCR") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("PDF") })
                        }
                        if (tab == 0) {
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
                                        val info = ImageTiling.probe(this@MainActivity, uri)
                                        vm.pickImage(uri, info)
                                    } catch (t: Throwable) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            t.message ?: "Gambar tidak valid",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }

                            when (val s = uiState) {
                                is OcrUiState.Done -> ResultScreen(
                                    uri = s.uri,
                                    result = s.result,
                                    onBack = {
                                        try {
                                            val info = ImageTiling.probe(this@MainActivity, s.uri)
                                            vm.backToImage(s.uri, info)
                                        } catch (_: Exception) { vm.cancel() }
                                    },
                                    onCopyAll = { txt ->
                                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("GrooxOCR", txt))
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Disalin (${s.result.bubbles.size} bubble)",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                                else -> HomeScreen(
                                    modelState = modelState,
                                    params = params,
                                    uiState = s,
                                    onPickImage = {
                                        picker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                    },
                                    onInstallModels = {
                                        vm.installModels(params.recMode != com.groox.ocr.data.RecMode.V6_ONLY)
                                    },
                                    onRun = { vm.runOcr(it) },
                                    onRecMode = { vm.setRecMode(it) },
                                    onReadingOrder = { vm.setReadingOrder(it) },
                                    onDetLongSide = { vm.setDetLongSide(it) },
                                    onBoxThresh = { vm.setBoxThresh(it) },
                                    onCancel = { vm.cancel() },
                                )
                            }
                        } else {
                            val pvm: PdfViewModel = viewModel(factory = factory)
                            PdfScreen(vm = pvm)
                        }
                    }
                }
            }
        }
    }
}
