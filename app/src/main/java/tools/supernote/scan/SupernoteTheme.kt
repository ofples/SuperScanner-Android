package tools.supernote.scan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ScanLightColors = lightColorScheme(
    primary = Color(0xFF315E55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E8E1),
    onPrimaryContainer = Color(0xFF153B33),
    secondary = Color(0xFF59635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EAE5),
    onSecondaryContainer = Color(0xFF28312B),
    background = Color(0xFFF7F8F5),
    onBackground = Color(0xFF191D1A),
    surface = Color(0xFFFCFCF9),
    onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFEBEFEB),
    onSurfaceVariant = Color(0xFF434A45),
    outline = Color(0xFF747C76),
    outlineVariant = Color(0xFFC5CDC7),
    surfaceTint = Color(0xFF315E55),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val ScanDarkColors = darkColorScheme(
    primary = Color(0xFFA3C9BC),
    onPrimary = Color(0xFF123B33),
    primaryContainer = Color(0xFF274A41),
    onPrimaryContainer = Color(0xFFD7E8E1),
    secondary = Color(0xFFC1CAC3),
    onSecondary = Color(0xFF29312C),
    secondaryContainer = Color(0xFF39433D),
    onSecondaryContainer = Color(0xFFE1E8E2),
    background = Color(0xFF151916),
    onBackground = Color(0xFFE3E7E2),
    surface = Color(0xFF1B201C),
    onSurface = Color(0xFFE3E7E2),
    surfaceVariant = Color(0xFF343B36),
    onSurfaceVariant = Color(0xFFC1CAC3),
    outline = Color(0xFF909991),
    outlineVariant = Color(0xFF59615C),
    surfaceTint = Color(0xFFA3C9BC),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
internal fun SupernoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ScanDarkColors else ScanLightColors,
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
