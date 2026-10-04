@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.pages.chat.component

import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.component.AnchoredBubblePopup
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.component.KcodeHazeState

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
