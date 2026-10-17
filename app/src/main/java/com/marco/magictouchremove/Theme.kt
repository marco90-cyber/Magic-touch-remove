package com.marco.magictouchremove

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Mint = Color(0xFF2EE6A6)
val Ink = Color(0xFF0B0F0E)
val Panel = Color(0xFF1A201E)

private val Light = lightColorScheme(
    primary = Color(0xFF006C51), onPrimary = Color.White,
    primaryContainer = Color(0xFF86F8CE), onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6358), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DA), onSecondaryContainer = Color(0xFF092016),
    tertiary = Color(0xFF3F6375), tertiaryContainer = Color(0xFFC2E8FD), onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFF5FBF7), onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF7), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE), onSurfaceVariant = Color(0xFF3F4944),
    surfaceContainer = Color(0xFFE9F0EB), surfaceContainerHigh = Color(0xFFE3EAE5), surfaceContainerLow = Color(0xFFEFF6F1),
    outline = Color(0xFF6F7973), outlineVariant = Color(0xFFBFC9C2),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6)
)

private val Dark = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF005139), onPrimaryContainer = Color(0xFF86F8CE),
    secondary = Color(0xFFB3CCBF), onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41), onSecondaryContainer = Color(0xFFCEE9DA),
    tertiary = Color(0xFFA7CCE0), tertiaryContainer = Color(0xFF254B5C), onTertiaryContainer = Color(0xFFC2E8FD),
    background = Ink, onBackground = Color(0xFFDFE4E0),
    surface = Color(0xFF0F1512), onSurface = Color(0xFFDFE4E0),
    surfaceVariant = Color(0xFF3F4944), onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceContainer = Color(0xFF1B211E), surfaceContainerHigh = Color(0xFF252B28), surfaceContainerLow = Color(0xFF171D1A),
    outline = Color(0xFF89938D), outlineVariant = Color(0xFF3F4944),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A)
)

private val MtShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp)
)

/** Tema Material 3: segue chiaro/scuro del telefono. */
@Composable
fun MagicTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = MtShapes, content = content)
}

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else if (bytes > 0) "${bytes / 1000} KB" else "—"
