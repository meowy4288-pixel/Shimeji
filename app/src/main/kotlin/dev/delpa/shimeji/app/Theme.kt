package dev.delpa.shimeji.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5FB3),
    secondary = Color(0xFF5B5B5B),
    background = Color(0xFFFBFBFF),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun ShimejiHarnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}