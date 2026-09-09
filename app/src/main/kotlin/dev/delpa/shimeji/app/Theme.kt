package dev.delpa.shimeji.app

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5FB3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFF585E71),
    onSecondary = Color.White,
    background = Color(0xFFFBFBFF),
    surface = Color(0xFFFEFBFF),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurface = Color(0xFF1A1C20),
    onSurfaceVariant = Color(0xFF44474E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    onPrimary = Color(0xFF0A2074),
    primaryContainer = Color(0xFF263F99),
    secondary = Color(0xFFC0C6D9),
    onSecondary = Color(0xFF2A3042),
    background = Color(0xFF121318),
    surface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFF2E3038),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFC4C6CF),
)

@Composable
fun ShimejiHarnessTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors

    // Match the status bar to the primary color.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
