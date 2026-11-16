package com.agent.eae.android.ui

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF4DD9CB),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF71F7E7),
    secondary = Color(0xFF8FBEF2),
    onSecondary = Color(0xFF00325B),
    secondaryContainer = Color(0xFF14477C),
    onSecondaryContainer = Color(0xFFD3E3FF),
    tertiary = Color(0xFFB7C4E8),
    onTertiary = Color(0xFF212E4A),
    background = Color(0xFF0E1416),
    onBackground = Color(0xFFDDE4E5),
    surface = Color(0xFF0E1416),
    onSurface = Color(0xFFDDE4E5),
    surfaceVariant = Color(0xFF2B3336),
    onSurfaceVariant = Color(0xFFBFC8CA),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A61),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF71F7E7),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF1B5F9E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E3FF),
    onSecondaryContainer = Color(0xFF001C39),
    tertiary = Color(0xFF3F4A63),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFDFB),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFDFB),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Terminal-ish style for sandbox stdout and error payloads. */
val LogMono: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

@Composable
fun IatTheme(
    useDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (useDark) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
