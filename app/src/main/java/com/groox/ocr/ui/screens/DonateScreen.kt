package com.groox.ocr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.groox.ocr.R

const val TELEGRAM_CHANNEL = "https://t.me/VasiliasPV"
const val TELEGRAM_DEV = "https://t.me/AnergiaPV"

/** Halaman donasi: foto QRIS + channel Telegram + kredit developer. */
@Composable
fun DonateScreen() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Donasi", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Dukung pengembangan GrooxOCR via QRIS di bawah ini.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.qris_ahmad_najmi),
                    contentDescription = "QRIS Ahmad Najmi Anshori",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "AHMAD NAJMI ANSHORI",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Scan pakai e-wallet / m-banking apa pun yang mendukung QRIS.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(
            onClick = { openLink(ctx, TELEGRAM_CHANNEL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Channel utama Telegram") }
        OutlinedButton(
            onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Telegram", TELEGRAM_CHANNEL))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Salin link channel") }
        Text(
            "Develop by @AnergiaPV",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { openLink(ctx, TELEGRAM_DEV) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Hubungi developer") }
    }
}

private fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {}
}
