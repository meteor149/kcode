package app.kcode.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cross-platform iOS-style sheet that rises from the bottom edge. On phones it
 * spans the viewport width and leaves a small reveal of the app behind; wider
 * layouts retain the same bottom anchoring while constraining reading width.
 */
@Composable
fun BottomSheetOverlay(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    compactHeightFraction: Float = .99f,
    sheetMaxWidth: androidx.compose.ui.unit.Dp = 680.dp,
    sheetMaxHeight: androidx.compose.ui.unit.Dp = 860.dp,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) .18f else 0f,
        animationSpec = tween(220),
        label = "bottom-sheet-scrim",
    )
    val dismiss = {
        if (!dismissing) {
            dismissing = true
            scope.launch {
                visible = false
                delay(240)
                onDismissRequest()
            }
        }
    }

    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 700.dp
            val sheetShape = if (compact) {
                RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
            } else {
                RoundedCornerShape(32.dp)
            }

            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = dismiss,
                    ),
            )

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(180)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(180)),
            ) {
                Surface(
                    modifier = modifier
                        .fillMaxWidth(if (compact) 1f else .78f)
                        .widthIn(max = sheetMaxWidth)
                        .fillMaxHeight(if (compact) compactHeightFraction else .9f)
                        .heightIn(max = sheetMaxHeight)
                        .imePadding(),
                    shape = sheetShape,
                    color = Color.White,
                    shadowElevation = 24.dp,
                ) {
                    content(dismiss)
                }
            }
        }
    }
}
