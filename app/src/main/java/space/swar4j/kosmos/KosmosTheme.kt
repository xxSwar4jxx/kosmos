package space.swar4j.kosmos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KosmosDarkColors = darkColorScheme(
    primary = Color(0xFFB8C7FF),
    onPrimary = Color(0xFF17264A),
    secondary = Color(0xFFBFC6DC),
    background = Color(0xFF101116),
    onBackground = Color(0xFFE5E5EC),
    surface = Color(0xFF191B22),
    onSurface = Color(0xFFE5E5EC),
    surfaceVariant = Color(0xFF292C35),
    onSurfaceVariant = Color(0xFFB9BBC7)
)

private val KosmosLightColors = lightColorScheme(
    primary = Color(0xFF405A9A),
    onPrimary = Color.White,
    secondary = Color(0xFF59627A),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF191B22),
    surface = Color.White,
    onSurface = Color(0xFF191B22),
    surfaceVariant = Color(0xFFE9EAF0),
    onSurfaceVariant = Color(0xFF555967)
)

@Composable
fun KosmosTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KosmosDarkColors else KosmosLightColors,
        content = content
    )
}
