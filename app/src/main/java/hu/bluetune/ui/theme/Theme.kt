package hu.bluetune.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cyan = Color(0xFF38D9FF)
val Violet = Color(0xFF8B7BFF)
val Warn = Color(0xFFFFB547)

private val Scheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00222C),
    primaryContainer = Color(0xFF0E3A48),
    onPrimaryContainer = Color(0xFFBFF1FF),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A2456),
    onSecondaryContainer = Color(0xFFE2DDFF),
    tertiary = Color(0xFFFF6B8B),
    background = Color(0xFF080B10),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF0F141B),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF1A222D),
    onSurfaceVariant = Color(0xFF9AA8B8),
    surfaceContainer = Color(0xFF121922),
    surfaceContainerHigh = Color(0xFF17202B),
    outline = Color(0xFF2C3846),
    outlineVariant = Color(0xFF222C38),
    error = Color(0xFFFF5370),
)

@Composable
fun BlueTuneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
