package ai.meteor.kcode.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

enum class PressScaleStyle(val pressedScale: Float) {
    /** Compact and circular actions need a clearly visible tactile response. */
    Button(.93f),

    /** Rows and cards use a quieter response so text remains stable. */
    Panel(.97f),
}

/** Shared press feedback for clickable kcode surfaces. Does not affect layout size. */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    style: PressScaleStyle = PressScaleStyle.Button,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) style.pressedScale else 1f,
        animationSpec = if (enabled && pressed) {
            tween(durationMillis = 110, easing = FastOutSlowInEasing)
        } else {
            spring(dampingRatio = .68f, stiffness = 380f)
        },
        label = "kcode-press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Click handling and press motion for custom rows, cards, and icon surfaces. */
@Composable
fun Modifier.pressClickable(
    enabled: Boolean = true,
    style: PressScaleStyle = PressScaleStyle.Panel,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return pressScale(interactionSource, style, enabled)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
}
