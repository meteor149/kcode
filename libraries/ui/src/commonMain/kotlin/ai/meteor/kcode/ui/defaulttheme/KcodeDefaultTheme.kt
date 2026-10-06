package ai.meteor.kcode.ui.defaulttheme

import ai.meteor.kcode.ui.design.KcodeExtendedColors
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Default colors used by the shipped chat theme and by native surfaces outside product roots. */
val KcodeDefaultColorScheme = lightColorScheme(
    primary = Color(0xFF8FD6A8),
    onPrimary = Color(0xFF202622),
    primaryContainer = Color(0xFFEBF8EF),
    onPrimaryContainer = Color(0xFF202622),
    secondary = Color(0xFF3E7653),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBCE8CC),
    onSecondaryContainer = Color(0xFF202622),
    background = Color.White,
    onBackground = Color(0xFF202622),
    surface = Color.White,
    onSurface = Color(0xFF202622),
    surfaceVariant = Color(0xFFF4F4F2),
    onSurfaceVariant = Color(0xFF727570),
    outline = Color(0xFF727570),
    outlineVariant = Color(0xFFE4E5E2),
    error = Color(0xFF9B403C),
    onError = Color.White,
    errorContainer = Color(0xFFF7E8E6),
    onErrorContainer = Color(0xFF9B403C),
    inverseSurface = Color(0xFF202622),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFF8FD6A8),
    surfaceTint = Color(0xFF8FD6A8),
)

val KcodeDefaultExtendedColors = KcodeExtendedColors(
    translucentControl = Color(0xCCF1F1EF),
    panel = Color(0xFFF4F4F2),
    navigationSurface = Color(0xFFF7F7F5),
    selectedSurface = Color(0xFFEAEAE8),
)
