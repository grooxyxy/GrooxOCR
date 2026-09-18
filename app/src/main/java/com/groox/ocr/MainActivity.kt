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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groox.ocr.ui.screens.DonateScreen
import com.groox.ocr.ui.screens.HomeScreen
import com.groox.ocr.ui.screens.ImageToolsScreen
import com.groox.ocr.ui.screens.PdfScreen
import com.groox.ocr.ui.screens.ResultScreen
import com.groox.ocr.ui.theme.GrooxTheme
import com.groox.ocr.ui.viewmodel.OcrUiState
import com.groox.ocr.ui.viewmodel.ImageToolsViewModel
import com.groox.ocr.ui.viewmodel.OcrViewModel
import com.groox.ocr.ui.viewmodel.PdfViewModel

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
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
                    ImageToolsViewModel::class.java -> ImageToolsViewModel() as T
                    else -> throw IllegalArgumentException(modelClass.name)
                }
            }
        }
        setContent {
            GrooxTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    topBar = {
                        Column {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        "GrooxOCR",
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                            ScrollableTabRow(
                                selectedTabIndex = tab,
                                edgePadding = 8.dp,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Tab(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    text = { Text("OCR") },
                                    icon = { Icon(Icons.Filled.DocumentScanner, null) },
                                )
                                Tab(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    text = { Text("PDF") },
                                    icon = { Icon(Icons.Filled.PictureAsPdf, null) },
                                )
                                Tab(
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    text = { Text("Gambar") },
                                    icon = { Icon(Icons.Filled.Image, null) },
                                )
                                Tab(
                                    selected = tab == 3,
                                    onClick = { tab = 3 },
                                    text = { Text("Donasi") },
                                    icon = { Icon(Icons.Filled.Favorite, null) },
                                )
                            }
                        }
                    },
                ) { inner ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(inner),
                    ) {
                        if (tab == 0) {
                            val vm: OcrViewModel = viewModel(factory = factory)
                            val uiState by vm.ui.collectAsState()
                            val modelState by vm.modelState.collectAsState()
                            val params by vm.params.collectAsState()
                            val picked by vm.picked.collectAsState()

                            val singlePicker = rememberLauncherForActivityResult(
                                ActivityResultContracts.OpenDocument()
                            ) { uri: Uri? ->
                                if (uri != null) {
                                    try {
                                        contentResolver.takePersistableUriPermission(
                                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        )
                                    } catch (_: Exception) {}
                                    vm.pickImages(this@MainActivity, listOf(uri))
                                }
                            }
                            val multiPicker = rememberLauncherForActivityResult(
                                ActivityResultContracts.OpenMultipleDocuments()
                            ) { uris: List<Uri> ->
                                if (uris.isNotEmpty()) {
                                    for (u in uris) {
                                        try {
                                            contentResolver.takePersistableUriPermission(
                                                u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            )
                                        } catch (_: Exception) {}
                                    }
                                    vm.pickImages(this@MainActivity, uris)
                                }
                            }

                            when (val s = uiState) {
                                is OcrUiState.DoneBatch -> ResultScreen(
                                    items = s.items,
                                    onBack = { vm.backToList() },
                                    onCopyAll = { txt ->
                                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("GrooxOCR", txt))
                                        val n = s.items.sumOf { it.result.bubbles.size }
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Disalin ($n bubble, ${s.items.size} gambar)",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                                else -> HomeScreen(
                                    modelState = modelState,
                                    params = params,
                                    uiState = s,
                                    picked = picked,
                                    onPickImage = {
                                        singlePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                    },
                                    onPickImages = {
                                        multiPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                    },
                                    onRemovePicked = { vm.removePicked(it) },
                                    onClearPicked = { vm.clearPicked() },
                                    onInstallModels = {
                                        vm.installModels(params.recMode != com.groox.ocr.data.RecMode.V6_ONLY)
                                    },
                                    onRunBatch = { vm.runOcrBatch(this@MainActivity) },
                                    onRecMode = { vm.setRecMode(it) },
                                    onReadingOrder = { vm.setReadingOrder(it) },
                                    onDetLongSide = { vm.setDetLongSide(it) },
                                    onBoxThresh = { vm.setBoxThresh(it) },
                                    onCancel = { vm.cancel() },
                                )
                            }
                        } else if (tab == 1) {
                            val pvm: PdfViewModel = viewModel(factory = factory)
                            PdfScreen(vm = pvm)
                        } else if (tab == 2) {
                            val tvm: ImageToolsViewModel = viewModel(factory = factory)
                            ImageToolsScreen(vm = tvm)
                        } else {
                            DonateScreen()
                        }
                    }
                }
            }
        }
    }
}
