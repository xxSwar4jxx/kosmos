package space.swar4j.kosmos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

private val KosmosAmberDark = darkColorScheme(
    primary = Color(0xFFFFB547), onPrimary = Color(0xFF2A1908),
    primaryContainer = Color(0xFF563400), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFFFC66E), onSecondary = Color(0xFF2A1908),
    background = Color(0xFF090A0D), onBackground = Color(0xFFFFE9C7),
    surface = Color(0xFF15171C), onSurface = Color(0xFFFFE9C7),
    surfaceVariant = Color(0xFF24262D), onSurfaceVariant = Color(0xFFD0C2AE),
    outline = Color(0xFF80613D)
)

private val KosmosLight = lightColorScheme(
    primary = Color(0xFF875000), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0), onPrimaryContainer = Color(0xFF2A1908),
    secondary = Color(0xFF755A36), onSecondary = Color.White,
    background = Color(0xFFFFF8F0), onBackground = Color(0xFF211A12),
    surface = Color(0xFFFFF8F0), onSurface = Color(0xFF211A12),
    surfaceVariant = Color(0xFFF0E2D0), onSurfaceVariant = Color(0xFF514538),
    outline = Color(0xFF85745E)
)

@Composable
fun KosmosTheme(themeMode: String = "default", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        "light" -> false
        "dark", "default" -> true
        "system" -> systemDark
        else -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) KosmosAmberDark else KosmosLight,
        content = content
    )
}
