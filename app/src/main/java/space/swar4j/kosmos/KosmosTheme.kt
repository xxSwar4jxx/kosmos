package space.swar4j.kosmos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

private val AmberDark = darkColorScheme(
    primary = Color(0xFFFFB547), onPrimary = Color(0xFFFFE9C7),
    primaryContainer = Color(0xFF563400), onPrimaryContainer = Color(0xFFFFE9C7),
    secondary = Color(0xFFFFC66E), onSecondary = Color(0xFFFFE9C7),
    background = Color(0xFF090A0D), onBackground = Color(0xFFFFE9C7),
    surface = Color(0xFF15171C), onSurface = Color(0xFFFFE9C7),
    surfaceVariant = Color(0xFF24262D), onSurfaceVariant = Color(0xFFFFE9C7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF2A1908),
    errorContainer = Color(0xFF5C1A12), onErrorContainer = Color(0xFFFFE9C7),
    outline = Color(0xFF80613D), inverseSurface = Color(0xFFFFE9C7),
    inverseOnSurface = Color(0xFF090A0D), inversePrimary = Color(0xFF8B5000),
    surfaceTint = Color(0xFFFFB547)
)
private val PureDark = darkColorScheme(
    primary = Color(0xFFFFD08A), onPrimary = Color(0xFF241600),
    primaryContainer = Color(0xFF49300F), onPrimaryContainer = Color(0xFFFFE9C7),
    secondary = Color(0xFFE8C99A), onSecondary = Color(0xFF241600),
    background = Color.Black, onBackground = Color(0xFFFFE9C7),
    surface = Color(0xFF101010), onSurface = Color(0xFFFFE9C7),
    surfaceVariant = Color(0xFF202020), onSurfaceVariant = Color(0xFFFFE9C7),
    outline = Color(0xFF75634B), surfaceTint = Color(0xFFFFD08A)
)
private val ForestDark = darkColorScheme(
    primary = Color(0xFF9BCB9D), onPrimary = Color(0xFF102416),
    primaryContainer = Color(0xFF254A30), onPrimaryContainer = Color(0xFFD8F0D9),
    secondary = Color(0xFFB4D7B5), onSecondary = Color(0xFF142719),
    background = Color(0xFF07140D), onBackground = Color(0xFFE2F3E4),
    surface = Color(0xFF10251A), onSurface = Color(0xFFE2F3E4),
    surfaceVariant = Color(0xFF1B3525), onSurfaceVariant = Color(0xFFD0E7D2),
    outline = Color(0xFF54765A), surfaceTint = Color(0xFF9BCB9D)
)
private val CleanLight = lightColorScheme(
    primary = Color(0xFF875000), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0), onPrimaryContainer = Color(0xFF2A1908),
    secondary = Color(0xFF755A36), onSecondary = Color.White,
    background = Color.White, onBackground = Color(0xFF171717),
    surface = Color.White, onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF1F1F1), onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFF85745E)
)

@Composable
fun KosmosTheme(themeMode: String = "default", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val scheme = when (themeMode) {
        "default" -> AmberDark
        "light" -> CleanLight
        "dark" -> PureDark
        "forest" -> ForestDark
        "system" -> if (systemDark) PureDark else CleanLight
        else -> AmberDark
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
