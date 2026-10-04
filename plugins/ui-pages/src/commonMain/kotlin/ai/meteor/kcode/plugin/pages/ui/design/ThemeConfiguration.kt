package ai.meteor.kcode.plugin.pages.ui.design

import ai.meteor.kcode.ui.design.KcodeDesignTokens
import ai.meteor.kcode.ui.design.KcodeExtendedColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

internal data class ResolvedTheme(
    val colorScheme: ColorScheme,
    val shapes: Shapes,
    val extendedColors: KcodeExtendedColors,
    val typography: Typography,
    val designTokens: KcodeDesignTokens,
)

/** Validate deployment values before the active theme fiber is retired. */
internal fun resolveThemeConfiguration(value: Any?): ResolvedTheme {
    if (value is ResolvedTheme) return value
    val configuration = when (value) {
        Unit -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> error("Theme configuration must be Unit or a JSON object")
    }
    configuration.requireKeys(setOf("colors", "extendedColors", "spacing", "radius", "size", "glass", "overlay", "fontScale"))
    val colors = configuration.section("colors")
    colors.requireKeys(setOf("primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary", "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer", "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer", "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint", "inverseSurface", "inverseOnSurface", "error", "onError", "errorContainer", "onErrorContainer", "outline", "outlineVariant", "scrim", "surfaceBright", "surfaceDim", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest"))
    val scheme = defaultColorScheme.copy(
        primary = colors.color("primary", defaultColorScheme.primary),
        onPrimary = colors.color("onPrimary", defaultColorScheme.onPrimary),
        primaryContainer = colors.color("primaryContainer", defaultColorScheme.primaryContainer),
        onPrimaryContainer = colors.color("onPrimaryContainer", defaultColorScheme.onPrimaryContainer),
        inversePrimary = colors.color("inversePrimary", defaultColorScheme.inversePrimary),
        secondary = colors.color("secondary", defaultColorScheme.secondary),
        onSecondary = colors.color("onSecondary", defaultColorScheme.onSecondary),
        secondaryContainer = colors.color("secondaryContainer", defaultColorScheme.secondaryContainer),
        onSecondaryContainer = colors.color("onSecondaryContainer", defaultColorScheme.onSecondaryContainer),
        tertiary = colors.color("tertiary", defaultColorScheme.tertiary),
        onTertiary = colors.color("onTertiary", defaultColorScheme.onTertiary),
        tertiaryContainer = colors.color("tertiaryContainer", defaultColorScheme.tertiaryContainer),
        onTertiaryContainer = colors.color("onTertiaryContainer", defaultColorScheme.onTertiaryContainer),
        background = colors.color("background", defaultColorScheme.background),
        onBackground = colors.color("onBackground", defaultColorScheme.onBackground),
        surface = colors.color("surface", defaultColorScheme.surface),
        onSurface = colors.color("onSurface", defaultColorScheme.onSurface),
        surfaceVariant = colors.color("surfaceVariant", defaultColorScheme.surfaceVariant),
        onSurfaceVariant = colors.color("onSurfaceVariant", defaultColorScheme.onSurfaceVariant),
        surfaceTint = colors.color("surfaceTint", defaultColorScheme.surfaceTint),
        inverseSurface = colors.color("inverseSurface", defaultColorScheme.inverseSurface),
        inverseOnSurface = colors.color("inverseOnSurface", defaultColorScheme.inverseOnSurface),
        error = colors.color("error", defaultColorScheme.error),
        onError = colors.color("onError", defaultColorScheme.onError),
        errorContainer = colors.color("errorContainer", defaultColorScheme.errorContainer),
        onErrorContainer = colors.color("onErrorContainer", defaultColorScheme.onErrorContainer),
        outline = colors.color("outline", defaultColorScheme.outline),
        outlineVariant = colors.color("outlineVariant", defaultColorScheme.outlineVariant),
        scrim = colors.color("scrim", defaultColorScheme.scrim),
        surfaceBright = colors.color("surfaceBright", defaultColorScheme.surfaceBright),
        surfaceDim = colors.color("surfaceDim", defaultColorScheme.surfaceDim),
        surfaceContainer = colors.color("surfaceContainer", defaultColorScheme.surfaceContainer),
        surfaceContainerHigh = colors.color("surfaceContainerHigh", defaultColorScheme.surfaceContainerHigh),
        surfaceContainerHighest = colors.color("surfaceContainerHighest", defaultColorScheme.surfaceContainerHighest),
        surfaceContainerLow = colors.color("surfaceContainerLow", defaultColorScheme.surfaceContainerLow),
        surfaceContainerLowest = colors.color("surfaceContainerLowest", defaultColorScheme.surfaceContainerLowest),
    )
    val extended = configuration.section("extendedColors")
    extended.requireKeys(setOf("translucentControl", "panel", "navigationSurface", "selectedSurface"))
    val extendedColors = defaultExtendedColors.copy(
        translucentControl = extended.color("translucentControl", defaultExtendedColors.translucentControl),
        panel = extended.color("panel", defaultExtendedColors.panel),
        navigationSurface = extended.color("navigationSurface", defaultExtendedColors.navigationSurface),
        selectedSurface = extended.color("selectedSurface", defaultExtendedColors.selectedSurface),
    )
    val spacing = configuration.section("spacing")
    spacing.requireKeys(setOf("hair", "xs", "sm", "md", "lg", "xl", "xxl"))
    val radius = configuration.section("radius")
    radius.requireKeys(setOf("control", "card", "panel"))
    val size = configuration.section("size")
    size.requireKeys(setOf("touchTarget", "compactControl", "compactPermissionControl", "compactModelControl", "narrowModelControl", "floatingShadowGutter"))
    val glass = configuration.section("glass")
    glass.requireKeys(setOf("blurRadius", "tintOpacity", "noiseFactor"))
    val overlay = configuration.section("overlay")
    overlay.requireKeys(setOf("floatingSize", "floatingShadow", "bubbleMinWidth", "bubbleMaxWidth", "bubbleMaxHeight", "bubbleGap", "bubbleMargin", "bubbleShadow", "bubbleShadowHorizontalPadding", "bubbleShadowTopPadding", "bubbleShadowBottomPadding", "sheetMaxWidth", "sheetMaxHeight", "sheetCompactBreakpoint", "sheetCompactRadius", "sheetRadius", "sheetShadow"))
    val design = DefaultDesignTokens.copy(
        spacing = DefaultDesignTokens.spacing.copy(
            hair = spacing.number("hair", DefaultDesignTokens.spacing.hair.value, 0f..512f).dp,
            xs = spacing.number("xs", DefaultDesignTokens.spacing.xs.value, 0f..512f).dp,
            sm = spacing.number("sm", DefaultDesignTokens.spacing.sm.value, 0f..512f).dp,
            md = spacing.number("md", DefaultDesignTokens.spacing.md.value, 0f..512f).dp,
            lg = spacing.number("lg", DefaultDesignTokens.spacing.lg.value, 0f..512f).dp,
            xl = spacing.number("xl", DefaultDesignTokens.spacing.xl.value, 0f..512f).dp,
            xxl = spacing.number("xxl", DefaultDesignTokens.spacing.xxl.value, 0f..512f).dp,
        ),
        radius = DefaultDesignTokens.radius.copy(
            control = radius.number("control", DefaultDesignTokens.radius.control.value, 0f..512f).dp,
            card = radius.number("card", DefaultDesignTokens.radius.card.value, 0f..512f).dp,
            panel = radius.number("panel", DefaultDesignTokens.radius.panel.value, 0f..512f).dp,
        ),
        size = DefaultDesignTokens.size.copy(
            touchTarget = size.number("touchTarget", DefaultDesignTokens.size.touchTarget.value, 0f..512f).dp,
            compactControl = size.number("compactControl", DefaultDesignTokens.size.compactControl.value, 0f..512f).dp,
            compactPermissionControl = size.number("compactPermissionControl", DefaultDesignTokens.size.compactPermissionControl.value, 0f..512f).dp,
            compactModelControl = size.number("compactModelControl", DefaultDesignTokens.size.compactModelControl.value, 0f..512f).dp,
            narrowModelControl = size.number("narrowModelControl", DefaultDesignTokens.size.narrowModelControl.value, 0f..512f).dp,
            floatingShadowGutter = size.number("floatingShadowGutter", DefaultDesignTokens.size.floatingShadowGutter.value, 0f..512f).dp,
        ),
        overlay = DefaultDesignTokens.overlay.copy(
            floatingSize = overlay.number("floatingSize", DefaultDesignTokens.overlay.floatingSize.value, 0f..2048f).dp,
            floatingShadow = overlay.number("floatingShadow", DefaultDesignTokens.overlay.floatingShadow.value, 0f..2048f).dp,
            bubbleMinWidth = overlay.number("bubbleMinWidth", DefaultDesignTokens.overlay.bubbleMinWidth.value, 0f..2048f).dp,
            bubbleMaxWidth = overlay.number("bubbleMaxWidth", DefaultDesignTokens.overlay.bubbleMaxWidth.value, 0f..2048f).dp,
            bubbleMaxHeight = overlay.number("bubbleMaxHeight", DefaultDesignTokens.overlay.bubbleMaxHeight.value, 0f..2048f).dp,
            bubbleGap = overlay.number("bubbleGap", DefaultDesignTokens.overlay.bubbleGap.value, 0f..2048f).dp,
            bubbleMargin = overlay.number("bubbleMargin", DefaultDesignTokens.overlay.bubbleMargin.value, 0f..2048f).dp,
            bubbleShadow = overlay.number("bubbleShadow", DefaultDesignTokens.overlay.bubbleShadow.value, 0f..2048f).dp,
            bubbleShadowHorizontalPadding = overlay.number("bubbleShadowHorizontalPadding", DefaultDesignTokens.overlay.bubbleShadowHorizontalPadding.value, 0f..2048f).dp,
            bubbleShadowTopPadding = overlay.number("bubbleShadowTopPadding", DefaultDesignTokens.overlay.bubbleShadowTopPadding.value, 0f..2048f).dp,
            bubbleShadowBottomPadding = overlay.number("bubbleShadowBottomPadding", DefaultDesignTokens.overlay.bubbleShadowBottomPadding.value, 0f..2048f).dp,
            sheetMaxWidth = overlay.number("sheetMaxWidth", DefaultDesignTokens.overlay.sheetMaxWidth.value, 0f..2048f).dp,
            sheetMaxHeight = overlay.number("sheetMaxHeight", DefaultDesignTokens.overlay.sheetMaxHeight.value, 0f..2048f).dp,
            sheetCompactBreakpoint = overlay.number("sheetCompactBreakpoint", DefaultDesignTokens.overlay.sheetCompactBreakpoint.value, 0f..2048f).dp,
            sheetCompactRadius = overlay.number("sheetCompactRadius", DefaultDesignTokens.overlay.sheetCompactRadius.value, 0f..2048f).dp,
            sheetRadius = overlay.number("sheetRadius", DefaultDesignTokens.overlay.sheetRadius.value, 0f..2048f).dp,
            sheetShadow = overlay.number("sheetShadow", DefaultDesignTokens.overlay.sheetShadow.value, 0f..2048f).dp,
        ),
        glass = DefaultDesignTokens.glass.copy(
            blurRadius = glass.number("blurRadius", DefaultDesignTokens.glass.blurRadius.value, 0f..512f).dp,
            tintOpacity = glass.number("tintOpacity", DefaultDesignTokens.glass.tintOpacity, 0f..1f),
            noiseFactor = glass.number("noiseFactor", DefaultDesignTokens.glass.noiseFactor, 0f..1f),
        ),
    )
    require(design.overlay.bubbleMinWidth <= design.overlay.bubbleMaxWidth) {
        "Theme bubbleMinWidth must not exceed bubbleMaxWidth"
    }
    val fontScale = configuration.number("fontScale", 1f, 0.5f..3f)
    fun TextStyle.scaled(): TextStyle = copy(
        fontSize = if (fontSize.isSpecified) fontSize * fontScale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * fontScale else lineHeight,
        letterSpacing = if (letterSpacing.isSpecified) letterSpacing * fontScale else letterSpacing,
    )
    val typography = DefaultTypography.copy(
        displayLarge = DefaultTypography.displayLarge.scaled(),
        displayMedium = DefaultTypography.displayMedium.scaled(),
        displaySmall = DefaultTypography.displaySmall.scaled(),
        headlineLarge = DefaultTypography.headlineLarge.scaled(),
        headlineMedium = DefaultTypography.headlineMedium.scaled(),
        headlineSmall = DefaultTypography.headlineSmall.scaled(),
        titleLarge = DefaultTypography.titleLarge.scaled(),
        titleMedium = DefaultTypography.titleMedium.scaled(),
        titleSmall = DefaultTypography.titleSmall.scaled(),
        bodyLarge = DefaultTypography.bodyLarge.scaled(),
        bodyMedium = DefaultTypography.bodyMedium.scaled(),
        bodySmall = DefaultTypography.bodySmall.scaled(),
        labelLarge = DefaultTypography.labelLarge.scaled(),
        labelMedium = DefaultTypography.labelMedium.scaled(),
        labelSmall = DefaultTypography.labelSmall.scaled(),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(design.radius.control),
        small = RoundedCornerShape(design.radius.control),
        medium = RoundedCornerShape(design.radius.card),
        large = RoundedCornerShape(design.radius.panel),
        extraLarge = RoundedCornerShape(design.radius.panel),
    )
    return ResolvedTheme(scheme, shapes, extendedColors, typography, design)
}

private fun JsonObject.requireKeys(allowed: Set<String>) {
    require(keys.all { it in allowed }) { "Unknown theme keys: ${keys - allowed}" }
}

private fun JsonObject.section(key: String): JsonObject = if (key !in this) JsonObject(emptyMap()) else
    this[key] as? JsonObject ?: error("Theme '$key' must be an object")

private fun JsonObject.color(key: String, fallback: Color): Color {
    if (key !in this) return fallback
    val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("Theme color '$key' must be a hex string")
    require(value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Theme color '$key' must be #RRGGBB or #AARRGGBB" }
    val argb = value.drop(1).toLong(16) or if (value.length == 7) 0xFF000000L else 0L
    return Color(argb)
}

private fun JsonObject.number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float {
    if (key !in this) return fallback
    val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
        ?: error("Theme '$key' must be a number")
    require(value.isFinite() && value in range) { "Theme '$key' must be within $range" }
    return value
}
