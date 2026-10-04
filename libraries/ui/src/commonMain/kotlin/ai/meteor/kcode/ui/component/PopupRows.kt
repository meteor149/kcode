package ai.meteor.kcode.ui.component

import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PopupNavigationRow(
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
            KcodeIcon(KcodeIconAsset.ChevronRight, Ink, Modifier.padding(start = KcodeSpacing.sm).size(18.dp))
        }
    }
}

@Composable
fun PopupSectionLabel(label: String) {
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
fun PopupChoiceRow(
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
            if (selected) KcodeIcon(KcodeIconAsset.Check, Ink, Modifier.size(18.dp))
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
        if (showChevron) KcodeIcon(KcodeIconAsset.ChevronRight, Ink, Modifier.padding(start = KcodeSpacing.xs).size(18.dp))
    }
}
