package ai.meteor.kcode.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

@Immutable
data class KcodeSpacingTokens(
    val hair: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
)

@Immutable
data class KcodeRadiusTokens(
    val control: Dp,
    val card: Dp,
    val panel: Dp,
)

@Immutable
data class KcodeSizeTokens(
    val touchTarget: Dp,
    val compactControl: Dp,
    val compactPermissionControl: Dp,
    val compactModelControl: Dp,
    val narrowModelControl: Dp,
    val floatingShadowGutter: Dp,
)

@Immutable
data class KcodeOverlayTokens(
    val floatingSize: Dp,
    val floatingShadow: Dp,
    val bubbleMinWidth: Dp,
    val bubbleMaxWidth: Dp,
    val bubbleMaxHeight: Dp,
    val bubbleGap: Dp,
    val bubbleMargin: Dp,
    val bubbleShadow: Dp,
    val bubbleShadowHorizontalPadding: Dp,
    val bubbleShadowTopPadding: Dp,
    val bubbleShadowBottomPadding: Dp,
    val sheetMaxWidth: Dp,
    val sheetMaxHeight: Dp,
    val sheetCompactBreakpoint: Dp,
    val sheetCompactRadius: Dp,
    val sheetRadius: Dp,
    val sheetShadow: Dp,
)

@Immutable
data class KcodeGlassTokens(
    val blurRadius: Dp,
    val tintOpacity: Float,
    val noiseFactor: Float,
)

@Immutable
data class KcodeDesignTokens(
    val spacing: KcodeSpacingTokens,
    val radius: KcodeRadiusTokens,
    val size: KcodeSizeTokens,
    val glass: KcodeGlassTokens,
    val overlay: KcodeOverlayTokens,
)

internal val LocalKcodeDesignTokens = staticCompositionLocalOf<KcodeDesignTokens> {
    error("KcodeTheme must supply design tokens")
}

val KcodeOverlay: KcodeOverlayTokens
    @Composable get() = LocalKcodeDesignTokens.current.overlay

val KcodeGlass: KcodeGlassTokens
    @Composable get() = LocalKcodeDesignTokens.current.glass

object KcodeSpacing {
    val hair: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.hair
    val xs: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.xs
    val sm: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.sm
    val md: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.md
    val lg: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.lg
    val xl: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.xl
    val xxl: Dp
        @Composable get() = LocalKcodeDesignTokens.current.spacing.xxl
}

object KcodeRadius {
    val control: Dp
        @Composable get() = LocalKcodeDesignTokens.current.radius.control
    val card: Dp
        @Composable get() = LocalKcodeDesignTokens.current.radius.card
    val panel: Dp
        @Composable get() = LocalKcodeDesignTokens.current.radius.panel
}

object KcodeSize {
    val touchTarget: Dp
        @Composable get() = LocalKcodeDesignTokens.current.size.touchTarget
    val compactControl: Dp
        @Composable get() = LocalKcodeDesignTokens.current.size.compactControl
    val compactPermissionControl: Dp
        @Composable get() = LocalKcodeDesignTokens.current.size.compactPermissionControl
    val compactModelControl: Dp
        @Composable get() = LocalKcodeDesignTokens.current.size.compactModelControl
    val narrowModelControl: Dp
        @Composable get() = LocalKcodeDesignTokens.current.size.narrowModelControl
    val floatingShadowGutter: Dp
        @Composable get() = LocalKcodeDesignTokens.current.size.floatingShadowGutter
}
