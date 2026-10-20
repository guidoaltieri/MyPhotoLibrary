package io.github.guidoaltieri.myphotolibrary.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Accent = Color(0xFF0A84FF)

private val Light = lightColorScheme(primary = Color(0xFF007AFF), secondary = Color(0xFF5856D6), tertiary = Color(0xFFFF2D55),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF2F2F7))
private val Dark = darkColorScheme(primary = Accent, secondary = Color(0xFF5E5CE6), tertiary = Color(0xFFFF375F),
    background = Color(0xFF1C1C1E), surface = Color(0xFF1C1C1E), surfaceVariant = Color(0xFF2C2C2E))

/** Tema come in ArchPhotos: come il sistema, chiaro, scuro e nero (neri puri, per OLED e per guardare le foto). */
@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    var scheme: ColorScheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) Dark else Light
    if (theme == "black") scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF0A0A0A),
        surfaceContainerLow = Color(0xFF050505), surfaceContainerHigh = Color(0xFF121212), surfaceVariant = Color(0xFF151515))
    MaterialTheme(colorScheme = scheme, content = content)
}
