@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode.ui.pages.chat

import app.kcode.ui.design.Panel
import app.kcode.ui.design.Ink
import app.kcode.ui.design.SoftInk
import app.kcode.ui.design.Hairline

import app.kcode.ui.design.KcodeRadius
import app.kcode.ui.design.KcodeSpacing
import app.kcode.ui.component.AnchoredBubblePopup
import app.kcode.ui.component.BubblePlacement
import app.kcode.ui.component.PressScaleStyle
import app.kcode.ui.component.pressScale
import app.kcode.localization.text
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun KcodeBubblePopup(
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
        surfaceColor = Color.White.copy(alpha = .985f),
        borderColor = Hairline.copy(alpha = .72f),
        focusable = focusable,
        content = content,
    )
}

@Composable
internal fun PopupNavigationRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val action = if (onClick == null) Modifier else Modifier
        .hoverable(interaction)
        .pressScale(interaction, PressScaleStyle.Panel)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered && onClick != null) Panel.copy(alpha = .72f) else Color.Transparent)
            .then(action).padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    Modifier.padding(top = KcodeSpacing.hair),
                    color = SoftInk.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Text("›", Modifier.padding(start = KcodeSpacing.sm), color = Ink, fontSize = 28.sp, lineHeight = 28.sp)
        }
    }
}

@Composable
internal fun PopupSectionLabel(label: String) {
    Text(
        label,
        Modifier.fillMaxWidth().padding(
            start = KcodeSpacing.md,
            end = KcodeSpacing.md,
            top = KcodeSpacing.sm,
            bottom = KcodeSpacing.hair,
        ),
        color = SoftInk.copy(alpha = .78f),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
internal fun PopupChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    showChevron: Boolean = false,
    titleColor: Color = Ink,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered) Panel.copy(alpha = .72f) else Color.Transparent)
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Panel)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = KcodeSpacing.sm, end = KcodeSpacing.md, top = KcodeSpacing.xs, bottom = KcodeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(KcodeSpacing.xl), contentAlignment = Alignment.CenterStart) {
            if (selected) Text("✓", color = Ink, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    Modifier.padding(top = KcodeSpacing.hair),
                    color = SoftInk.copy(alpha = .74f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (showChevron) Text("›", Modifier.padding(start = KcodeSpacing.xs), color = Ink, fontSize = 26.sp, lineHeight = 26.sp)
    }
}
