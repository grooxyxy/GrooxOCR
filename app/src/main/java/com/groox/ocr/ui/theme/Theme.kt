package com.groox.ocr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF0B3050),
    secondary = Color(0xFF5F6368),
    tertiary = Color(0xFF188038),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F3F4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF0B3050),
    primaryContainer = Color(0xFF1A73E8),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFBDC7D1),
    tertiary = Color(0xFF7BDA9B),
)

@Composable
fun GrooxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Material You (Android 12+) bila tersedia; fallback ke palet Groox.
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
