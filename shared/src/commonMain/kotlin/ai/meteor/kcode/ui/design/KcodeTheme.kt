package ai.meteor.kcode.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand and extended surface tokens that do not have a one-to-one Material color role.
 *
 * UI code should prefer [MaterialTheme.colorScheme] for standard controls. These tokens are kept
 * for kcode-specific surfaces such as translucent controls, the navigation rail, and brand marks.
 */
val Mist = Color(0xCCF1F1EF)
val Panel = Color(0xFFF4F4F2)
val Paper = Color.White
val SidebarPaper = Color(0xFFF7F7F5)
val Mint = Color(0xFFBCE8CC)
val PaleMint = Color(0xFFEBF8EF)
val Leaf = Color(0xFF8FD6A8)
val LeafInk = Color(0xFF3E7653)
val Ink = Color(0xFF202622)
val SoftInk = Color(0xFF727570)
val Hairline = Color(0xFFE4E5E2)
val Error = Color(0xFF9B403C)

/** Extra semantic roles used by kcode beyond Material's standard [ColorScheme] roles. */
@Immutable
data class KcodeExtendedColors(
    val translucentControl: Color,
    val panel: Color,
    val navigationSurface: Color,
)

private val kcodeLightExtendedColors = KcodeExtendedColors(
    translucentControl = Mist,
    panel = Panel,
    navigationSurface = SidebarPaper,
)

private val LocalKcodeExtendedColors = staticCompositionLocalOf { kcodeLightExtendedColors }

/** Access to kcode-only color roles; prefer [MaterialTheme.colorScheme] for standard roles. */
val MaterialTheme.kcodeColors: KcodeExtendedColors
    @Composable get() = LocalKcodeExtendedColors.current

private val kcodeLightColorScheme = lightColorScheme(
    primary = Leaf,
    onPrimary = Ink,
    primaryContainer = PaleMint,
    onPrimaryContainer = Ink,
    secondary = LeafInk,
    onSecondary = Paper,
    secondaryContainer = Mint,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = SoftInk,
    outline = SoftInk,
    outlineVariant = Hairline,
    error = Error,
    onError = Paper,
    errorContainer = Color(0xFFF7E8E6),
    onErrorContainer = Error,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Leaf,
    surfaceTint = Leaf,
)

private val kcodeShapes = Shapes(
    extraSmall = RoundedCornerShape(KcodeRadius.control),
    small = RoundedCornerShape(KcodeRadius.control),
    medium = RoundedCornerShape(KcodeRadius.card),
    large = RoundedCornerShape(KcodeRadius.panel),
    extraLarge = RoundedCornerShape(KcodeRadius.panel),
)

/** The single application theme entry point for every Compose target. */
@Composable
fun KcodeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKcodeExtendedColors provides kcodeLightExtendedColors) {
        MaterialTheme(
            colorScheme = kcodeLightColorScheme,
            typography = KcodeTypography,
            shapes = kcodeShapes,
            content = content,
        )
    }
}
