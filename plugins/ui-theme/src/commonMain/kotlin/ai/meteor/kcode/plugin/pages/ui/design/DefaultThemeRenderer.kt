package ai.meteor.kcode.plugin.pages.ui.design

import ai.meteor.kcode.plugin.ui.api.ThemeRenderer
import ai.meteor.kcode.ui.design.KcodeExtendedColors
import ai.meteor.kcode.ui.design.KcodeTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

internal val defaultColorScheme = lightColorScheme(
    primary = DefaultLeaf,
    onPrimary = DefaultInk,
    primaryContainer = DefaultPaleMint,
    onPrimaryContainer = DefaultInk,
    secondary = DefaultLeafInk,
    onSecondary = DefaultPaper,
    secondaryContainer = DefaultMint,
    onSecondaryContainer = DefaultInk,
    background = DefaultPaper,
    onBackground = DefaultInk,
    surface = DefaultPaper,
    onSurface = DefaultInk,
    surfaceVariant = DefaultPanel,
    onSurfaceVariant = DefaultSoftInk,
    outline = DefaultSoftInk,
    outlineVariant = DefaultHairline,
    error = DefaultError,
    onError = DefaultPaper,
    errorContainer = DefaultErrorContainer,
    onErrorContainer = DefaultError,
    inverseSurface = DefaultInk,
    inverseOnSurface = DefaultPaper,
    inversePrimary = DefaultLeaf,
    surfaceTint = DefaultLeaf,
)

internal val defaultExtendedColors = KcodeExtendedColors(DefaultMist, DefaultPanel, DefaultSidebarPaper, DefaultSelectedSurface)

class DefaultThemeRenderer internal constructor(private val specification: ResolvedTheme) : ThemeRenderer {
    @Composable
    override fun Render(content: @Composable () -> Unit) = KcodeTheme(
        colorScheme = specification.colorScheme,
        shapes = specification.shapes,
        extendedColors = specification.extendedColors,
        typography = specification.typography,
        designTokens = specification.designTokens,
        content = content,
    )
}
