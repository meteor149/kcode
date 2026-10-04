package ai.meteor.kcode.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Compatibility role names project the active theme; no product palette is stored in the SDK. */
val Mist: Color
    @Composable get() = MaterialTheme.kcodeColors.translucentControl

val Panel: Color
    @Composable get() = MaterialTheme.kcodeColors.panel

val Paper: Color
    @Composable get() = MaterialTheme.colorScheme.surface

val SidebarPaper: Color
    @Composable get() = MaterialTheme.kcodeColors.navigationSurface

val Mint: Color
    @Composable get() = MaterialTheme.colorScheme.secondaryContainer

val PaleMint: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer

val Leaf: Color
    @Composable get() = MaterialTheme.colorScheme.primary

val LeafInk: Color
    @Composable get() = MaterialTheme.colorScheme.secondary

val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

val SoftInk: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

val Hairline: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant

val Error: Color
    @Composable get() = MaterialTheme.colorScheme.error

/** Extra semantic roles used by kcode beyond Material's standard [ColorScheme] roles. */
@Immutable
data class KcodeExtendedColors(
    val translucentControl: Color,
    val panel: Color,
    val navigationSurface: Color,
    val selectedSurface: Color,
)

private val LocalKcodeExtendedColors = staticCompositionLocalOf<KcodeExtendedColors> {
    error("KcodeTheme must supply extended colors")
}

/** Access to kcode-only color roles; prefer [MaterialTheme.colorScheme] for standard roles. */
val MaterialTheme.kcodeColors: KcodeExtendedColors
    @Composable get() = LocalKcodeExtendedColors.current

/** The single theme entry; its selected provider supplies the product appearance. */
@Composable
fun KcodeTheme(
    colorScheme: ColorScheme,
    shapes: Shapes,
    extendedColors: KcodeExtendedColors,
    typography: androidx.compose.material3.Typography,
    designTokens: KcodeDesignTokens,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKcodeExtendedColors provides extendedColors,
        LocalKcodeDesignTokens provides designTokens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
