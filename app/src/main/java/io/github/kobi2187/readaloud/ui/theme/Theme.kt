package io.github.kobi2187.readaloud.ui.theme

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

private val TelegramBlue = Color(0xFF229ED9)

private val Light = lightColorScheme(
    primary = Color(0xFF0B76B0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE6F7),
    onPrimaryContainer = Color(0xFF00344F),
    secondary = Color(0xFF4F616E),
    tertiary = TelegramBlue,
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFF7F9FC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DCDFF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004C70),
    onPrimaryContainer = Color(0xFFCDE6F7),
    secondary = Color(0xFFB6C9D8),
    tertiary = TelegramBlue,
    background = Color(0xFF10141A),
    surface = Color(0xFF10141A),
)

@Composable
fun ReadAloudTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
