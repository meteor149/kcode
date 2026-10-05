@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.pages.chat.component

import ai.meteor.kcode.ui.component.AnchoredBubblePopup
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.design.Hairline
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalChatHazeState = staticCompositionLocalOf<KcodeHazeState?> { null }

@Composable
fun KcodeBubblePopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    placement: BubblePlacement,
    modifier: Modifier = Modifier,
    minWidth: Dp = 248.dp,
    maxWidth: Dp = 300.dp,
    maxHeight: Dp = 620.dp,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnchoredBubblePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        placement = placement,
        modifier = modifier,
        minWidth = minWidth,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        shape = MaterialTheme.shapes.extraLarge,
        surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
        borderColor = Hairline.copy(alpha = .72f),
        hazeState = LocalChatHazeState.current,
        focusable = focusable,
        content = content,
    )
}
