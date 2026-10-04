package ai.meteor.kcode.plugin.pages.ui.design

import ai.meteor.kcode.ui.design.KcodeOverlayTokens
import ai.meteor.kcode.ui.design.KcodeDesignTokens
import ai.meteor.kcode.ui.design.KcodeSpacingTokens
import ai.meteor.kcode.ui.design.KcodeRadiusTokens
import ai.meteor.kcode.ui.design.KcodeSizeTokens
import ai.meteor.kcode.ui.design.KcodeGlassTokens
import androidx.compose.ui.unit.dp

internal val DefaultDesignTokens = KcodeDesignTokens(
    spacing = KcodeSpacingTokens(
        hair = 4.dp,
        xs = 8.dp,
        sm = 12.dp,
        md = 16.dp,
        lg = 24.dp,
        xl = 32.dp,
        xxl = 40.dp,
    ),
    radius = KcodeRadiusTokens(
        control = 12.dp,
        card = 20.dp,
        panel = 28.dp,
    ),
    size = KcodeSizeTokens(
        touchTarget = 48.dp,
        compactControl = 40.dp,
        compactPermissionControl = 80.dp,
        compactModelControl = 144.dp,
        narrowModelControl = 136.dp,
        floatingShadowGutter = 16.dp,
    ),
    overlay = KcodeOverlayTokens(
        floatingSize = 52.dp,
        floatingShadow = 7.dp,
        bubbleMinWidth = 276.dp,
        bubbleMaxWidth = 340.dp,
        bubbleMaxHeight = 680.dp,
        bubbleGap = 10.dp,
        bubbleMargin = 12.dp,
        bubbleShadow = 14.dp,
        bubbleShadowHorizontalPadding = 32.dp,
        bubbleShadowTopPadding = 28.dp,
        bubbleShadowBottomPadding = 48.dp,
        sheetMaxWidth = 680.dp,
        sheetMaxHeight = 860.dp,
        sheetCompactBreakpoint = 700.dp,
        sheetCompactRadius = 34.dp,
        sheetRadius = 32.dp,
        sheetShadow = 24.dp,
    ),
    glass = KcodeGlassTokens(blurRadius = 16.dp, tintOpacity = 0.72f, noiseFactor = 0.025f),
)
