package app.compendium

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pal {
    val Red = Color(0xFFDC1E2E)
    val Ink = Color(0xFF11141D)
    val Panel = Color(0xFF1B2030)
    val PanelHi = Color(0xFF262D44)
    val Text = Color(0xFFEDEFF5)
    val Muted = Color(0xFF9AA2BA)
    val Ok = Color(0xFF3FB950)
    val Warn = Color(0xFFE3A72F)
}

private val scheme = darkColorScheme(
    primary = Pal.Red,
    onPrimary = Color.White,
    secondary = Pal.Red,
    onSecondary = Color.White,
    background = Pal.Ink,
    onBackground = Pal.Text,
    surface = Pal.Panel,
    onSurface = Pal.Text,
    surfaceVariant = Pal.PanelHi,
    onSurfaceVariant = Pal.Muted,
    surfaceContainer = Pal.Panel,
    surfaceContainerHigh = Pal.PanelHi,
    outline = Pal.Muted,
    error = Color(0xFFFF6B6B),
)

@Composable
fun CompendiumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
