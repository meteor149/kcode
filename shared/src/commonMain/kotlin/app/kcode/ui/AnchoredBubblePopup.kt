package app.kcode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

enum class BubblePlacement { Above, Below }

/**
 * A reusable non-modal bubble anchored to the composable that calls it.
 *
 * The popup stays mounted for its exit animation, dismisses on outside click/back,
 * and clamps itself to the visible window instead of expanding a full-screen dialog.
 */
@Composable
fun AnchoredBubblePopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    placement: BubblePlacement,
    shape: Shape,
    surfaceColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    minWidth: Dp = 276.dp,
    maxWidth: Dp = 340.dp,
    maxHeight: Dp = 680.dp,
    gap: Dp = 10.dp,
    windowMargin: Dp = 12.dp,
    shadowElevation: Dp = 14.dp,
    shadowHorizontalPadding: Dp = 32.dp,
    shadowTopPadding: Dp = 28.dp,
    shadowBottomPadding: Dp = 48.dp,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { visibility.targetState = expanded }
    if (!visibility.currentState && !visibility.targetState) return

    val gapPx = androidx.compose.ui.platform.LocalDensity.current.run { gap.roundToPx() }
    val marginPx = androidx.compose.ui.platform.LocalDensity.current.run { windowMargin.roundToPx() }
    val shadowHorizontalPaddingPx = androidx.compose.ui.platform.LocalDensity.current.run { shadowHorizontalPadding.roundToPx() }
    val shadowTopPaddingPx = androidx.compose.ui.platform.LocalDensity.current.run { shadowTopPadding.roundToPx() }
    val shadowBottomPaddingPx = androidx.compose.ui.platform.LocalDensity.current.run { shadowBottomPadding.roundToPx() }
    val positionProvider = remember(
        placement,
        gapPx,
        marginPx,
        shadowHorizontalPaddingPx,
        shadowTopPaddingPx,
        shadowBottomPaddingPx,
    ) {
        BubblePositionProvider(
            placement = placement,
            gap = gapPx,
            margin = marginPx,
            shadowHorizontalPadding = shadowHorizontalPaddingPx,
            shadowTopPadding = shadowTopPaddingPx,
            shadowBottomPadding = shadowBottomPaddingPx,
        )
    }
    val transformOrigin = if (placement == BubblePlacement.Above) {
        TransformOrigin(0.92f, 1f)
    } else {
        TransformOrigin(0.92f, 0f)
    }
    val initialOffset: (Int) -> Int = { height ->
        if (placement == BubblePlacement.Above) height / 14 else -height / 14
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = focusable),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(150)) +
                scaleIn(tween(190), initialScale = .86f, transformOrigin = transformOrigin) +
                slideInVertically(tween(190), initialOffsetY = initialOffset),
            exit = fadeOut(tween(110)) +
                scaleOut(tween(140), targetScale = .9f, transformOrigin = transformOrigin) +
                slideOutVertically(tween(140), targetOffsetY = initialOffset),
        ) {
            // Android elevation shadows extend farther below the surface than their nominal
            // elevation. Reserve asymmetric room inside the native Popup window so neither
            // the window nor AnimatedVisibility's rectangular layer clips the penumbra.
            Box(
                Modifier.padding(
                    start = shadowHorizontalPadding,
                    top = shadowTopPadding,
                    end = shadowHorizontalPadding,
                    bottom = shadowBottomPadding,
                ),
            ) {
                Surface(
                    modifier = modifier.widthIn(min = minWidth, max = maxWidth).heightIn(max = maxHeight)
                        .shadow(shadowElevation, shape = shape, clip = false),
                    shape = shape,
                    color = surfaceColor,
                    border = BorderStroke(1.dp, borderColor),
                    shadowElevation = 0.dp,
                ) {
                    Column(content = content)
                }
            }
        }
    }
}

private class BubblePositionProvider(
    private val placement: BubblePlacement,
    private val gap: Int,
    private val margin: Int,
    private val shadowHorizontalPadding: Int,
    private val shadowTopPadding: Int,
    private val shadowBottomPadding: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val popupFitsHorizontalMargins = popupContentSize.width + margin * 2 <= windowSize.width
        val minX = if (popupFitsHorizontalMargins) margin else 0
        val maxX = if (popupFitsHorizontalMargins) {
            windowSize.width - popupContentSize.width - margin
        } else {
            (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        }
        val endAlignedX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width + shadowHorizontalPadding
        } else {
            anchorBounds.left - shadowHorizontalPadding
        }
        val x = endAlignedX.coerceIn(minX, maxX)

        // Position the visible rounded surface—not its transparent shadow gutter—at the gap.
        val above = anchorBounds.top - popupContentSize.height - gap + shadowBottomPadding
        val below = anchorBounds.bottom + gap - shadowTopPadding
        val popupFitsVerticalMargins = popupContentSize.height + margin * 2 <= windowSize.height
        val minY = if (popupFitsVerticalMargins) margin else 0
        val maxY = if (popupFitsVerticalMargins) {
            windowSize.height - popupContentSize.height - margin
        } else {
            (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        val preferredY = if (placement == BubblePlacement.Above) above else below
        val alternateY = if (placement == BubblePlacement.Above) below else above
        val preferredFits = preferredY in minY..maxY
        val y = (if (preferredFits) preferredY else alternateY).coerceIn(minY, maxY)
        return IntOffset(x, y)
    }
}
