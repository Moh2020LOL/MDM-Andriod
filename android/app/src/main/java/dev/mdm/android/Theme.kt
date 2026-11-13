package dev.mdm.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The blue, white and slate of the Free Download Manager phone screens this app is styled after. */
object MdmColors {
    val Blue = Color(0xFF1E88E5)
    val BlueDark = Color(0xFF1565C0)
    val Green = Color(0xFF43A047)
    val Orange = Color(0xFFFB8C00)
    val Red = Color(0xFFE53935)
    val Slate = Color(0xFF263238)
    val SlateDeep = Color(0xFF1B262C)
}

private val Light = lightColorScheme(
    primary = MdmColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FB),
    onPrimaryContainer = Color(0xFF0D3B66),
    secondary = MdmColors.BlueDark,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F4F7),
    onSurface = Color(0xFF1B1F23),
    onSurfaceVariant = Color(0xFF5F6B76),
    outlineVariant = Color(0xFFDDE3E8),
    error = MdmColors.Red,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DA3F0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF123B63),
    onPrimaryContainer = Color(0xFFD6E8FB),
    secondary = Color(0xFF4DA3F0),
    background = MdmColors.SlateDeep,
    surface = MdmColors.SlateDeep,
    surfaceVariant = MdmColors.Slate,
    onSurface = Color(0xFFE6EBEF),
    onSurfaceVariant = Color(0xFF9FB0BC),
    outlineVariant = Color(0xFF37474F),
    error = Color(0xFFEF5350),
)

@Composable
fun MdmTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
