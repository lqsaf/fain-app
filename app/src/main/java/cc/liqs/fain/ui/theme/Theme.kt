package cc.liqs.fain.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val DarkColorScheme = darkColorScheme(
    background = Color.Black
)

private val LightColorScheme = lightColorScheme(
    background = Color.White
)

@Composable
fun FainTheme(
    customBackground: Color = Color.Black,
    darkTheme: Boolean = customBackground.luminance() <= 0.5f,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = baseScheme.copy(
        background = customBackground,
        surface = customBackground
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}