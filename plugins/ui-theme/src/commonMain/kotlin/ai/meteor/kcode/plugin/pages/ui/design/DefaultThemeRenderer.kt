package ai.meteor.kcode.plugin.pages.ui.design

import ai.meteor.kcode.plugin.ui.api.ThemeRenderer
import ai.meteor.kcode.ui.design.KcodeTheme
import ai.meteor.kcode.ui.defaulttheme.KcodeDefaultColorScheme
import ai.meteor.kcode.ui.defaulttheme.KcodeDefaultExtendedColors
import androidx.compose.runtime.Composable

internal val defaultColorScheme = KcodeDefaultColorScheme
internal val defaultExtendedColors = KcodeDefaultExtendedColors

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
