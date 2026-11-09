package space.swar4j.kosmos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KosmosAmberColors = darkColorScheme(
    primary = Color(0xFFFFB547), onPrimary = Color(0xFF2A1908),
    primaryContainer = Color(0xFF563400), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFFFC66E), onSecondary = Color(0xFF2A1908),
    background = Color(0xFF090A0D), onBackground = Color(0xFFFFE9C7),
    surface = Color(0xFF15171C), onSurface = Color(0xFFFFE9C7),
    surfaceVariant = Color(0xFF24262D), onSurfaceVariant = Color(0xFFD0C2AE),
    outline = Color(0xFF80613D)
)

@Composable
fun KosmosTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KosmosAmberColors, content = content)
}
