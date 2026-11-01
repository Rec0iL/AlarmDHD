package dev.rec0il.alarmdhd.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark only: this app gets looked at in a dark bedroom, with barely open eyes. */
private val AlarmColors = darkColorScheme(
    primary = Color(0xFFFFB454),
    onPrimary = Color(0xFF2B1800),
    primaryContainer = Color(0xFF4A3214),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFF7FC8F8),
    onSecondary = Color(0xFF00263D),
    secondaryContainer = Color(0xFF1E3A4F),
    onSecondaryContainer = Color(0xFFCDE8FB),
    tertiary = Color(0xFFFF8A80),
    onTertiary = Color(0xFF3B0A06),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF3A0403),
    errorContainer = Color(0xFF4A1A17),
    onErrorContainer = Color(0xFFFFD9D5),
    background = Color(0xFF101218),
    onBackground = Color(0xFFF1F2F6),
    surface = Color(0xFF101218),
    onSurface = Color(0xFFF1F2F6),
    surfaceVariant = Color(0xFF262A36),
    onSurfaceVariant = Color(0xFFB9BDCB),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF161922),
    surfaceContainer = Color(0xFF1B1E28),
    surfaceContainerHigh = Color(0xFF232733),
    surfaceContainerHighest = Color(0xFF2C313E),
    outline = Color(0xFF6B7080),
    outlineVariant = Color(0xFF3A3F4D),
)

/** The app stays dark on a phone set to a light theme, so the status bar icons have to stay light too. */
fun ComponentActivity.enableDarkEdgeToEdge() {
    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
}

@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AlarmColors, content = content)
}
