package space.swar4j.kosmos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KosmosAmberColors = darkColorScheme(
    primary = Color(0xFFFFB547), onPrimary = Color(0xFF241400),
    primaryContainer = Color(0xFF563400), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFFFC66E), onSecondary = Color(0xFF241400),
    background = Color(0xFF090A0D), onBackground = Color(0xFFF1EEE8),
    surface = Color(0xFF15171C), onSurface = Color(0xFFF1EEE8),
    surfaceVariant = Color(0xFF24262D), onSurfaceVariant = Color(0xFFB9B4AA),
    outline = Color(0xFF514638)
)

@Composable
fun KosmosTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KosmosAmberColors, content = content)
}
