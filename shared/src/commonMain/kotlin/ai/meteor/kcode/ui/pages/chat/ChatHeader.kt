@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.component.FloatingCircleButton

import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.model.ModelConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopChatHeader(
    conversationId: Long?,
    title: String,
    hasMessages: Boolean,
    selectionMode: Boolean,
    selectedCount: Int,
    configuration: ModelConfiguration?,
    onMenu: () -> Unit,
    onCancelSelection: () -> Unit,
    onSaveSelection: () -> Unit,
    onShareSelection: () -> Unit,
    onSaveConversation: () -> Unit,
    onShareConversation: () -> Unit,
    onMissingConfiguration: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
) {
    var exportExpanded by remember(conversationId) { mutableStateOf(false) }
    var selectionExportExpanded by remember(conversationId) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            FloatingCircleButton(
                description = text(UiText.Cancel),
                onClick = onCancelSelection,
                size = 42.dp,
            ) { Text("×", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Light) }
            Text(
                text(UiText.SelectedMessages, selectedCount),
                Modifier.padding(start = 12.dp).weight(1f),
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
            )
            Box {
                FloatingCircleButton(
                    description = text(UiText.ShareImage),
                    onClick = { selectionExportExpanded = true },
                    size = 42.dp,
                ) { ExportMark() }
                ExportOptionsBubble(
                    expanded = selectionExportExpanded,
                    placement = BubblePlacement.Below,
                    onDismissRequest = { selectionExportExpanded = false },
                    onSave = {
                        selectionExportExpanded = false
                        onSaveSelection()
                    },
                    onShare = {
                        selectionExportExpanded = false
                        onShareSelection()
                    },
                )
            }
            return@Row
        }

        QuietButton("☰", text(UiText.OpenSidebar), onMenu)
        Text(
            title,
            Modifier.padding(start = 13.dp).weight(1f),
            color = SoftInk,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            QuietButton("⇧", text(UiText.ExportConversation)) {
                if (hasMessages) exportExpanded = true
            }
            ExportOptionsBubble(
                expanded = exportExpanded,
                placement = BubblePlacement.Below,
                onDismissRequest = { exportExpanded = false },
                onSave = {
                    exportExpanded = false
                    onSaveConversation()
                },
                onShare = {
                    exportExpanded = false
                    onShareConversation()
                },
            )
        }
        ModelBadge(
            configuration = configuration,
            onMissingConfiguration = onMissingConfiguration,
            onConfigurationChange = onConfigurationChange,
        )
    }
    HorizontalDivider(color = Hairline, thickness = 0.5.dp)
}

@Composable
internal fun ExportOptionsBubble(
    expanded: Boolean,
    placement: BubblePlacement,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    KcodeBubblePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        placement = placement,
        minWidth = 216.dp,
        maxWidth = 256.dp,
        maxHeight = 320.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.sm)) {
            PopupNavigationRow(label = text(UiText.ExportConversation))
            HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = Hairline, thickness = .7.dp)
            PopupSectionLabel(text(UiText.ExportAction))
            PopupChoiceRow(
                title = text(UiText.SaveToPhotos),
                showChevron = true,
                onClick = onSave,
            )
            PopupChoiceRow(
                title = text(UiText.ShareImage),
                showChevron = true,
                onClick = onShare,
            )
        }
    }
}

@Composable
internal fun CompactChatHeader(
    modifier: Modifier = Modifier,
    selectionMode: Boolean,
    selectedCount: Int,
    onCancelSelection: () -> Unit,
    onSaveSelection: () -> Unit,
    onShareSelection: () -> Unit,
    onMenu: () -> Unit,
    onNew: () -> Unit,
    exportEnabled: Boolean,
    onExportSave: () -> Unit,
    onExportShare: () -> Unit,
) {
    var exportExpanded by remember { mutableStateOf(false) }
    var selectionExportExpanded by remember { mutableStateOf(false) }
    val newChatDescription = text(UiText.NewChat)
    val exportDescription = text(UiText.ExportConversation)
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val extraCompact = maxWidth < 360.dp
    Row(
        Modifier.fillMaxWidth()
            .height(if (extraCompact) 72.dp else 80.dp)
            .padding(horizontal = if (extraCompact) 12.dp else 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            FloatingCircleButton(
                description = text(UiText.Cancel),
                onClick = onCancelSelection,
                size = if (extraCompact) 48.dp else 52.dp,
            ) { Text("×", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Light) }
            Box {
                Surface(
                    modifier = Modifier.height(if (extraCompact) 48.dp else 52.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = .96f),
                    border = BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.pressClickable(
                            enabled = selectedCount > 0,
                            style = PressScaleStyle.Button,
                            onClick = { selectionExportExpanded = true },
                        ).padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text(UiText.SelectedMessages, selectedCount),
                            color = if (selectedCount > 0) Ink else SoftInk,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        ExportMark()
                    }
                }
                ExportOptionsBubble(
                    expanded = selectionExportExpanded,
                    placement = BubblePlacement.Below,
                    onDismissRequest = { selectionExportExpanded = false },
                    onSave = {
                        selectionExportExpanded = false
                        onSaveSelection()
                    },
                    onShare = {
                        selectionExportExpanded = false
                        onShareSelection()
                    },
                )
            }
            return@Row
        }
        FloatingCircleButton(
            description = text(UiText.OpenSidebar),
            onClick = onMenu,
            size = if (extraCompact) 48.dp else 52.dp,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = size.minDimension * .09f
                listOf(.25f, .5f, .75f).forEach { y ->
                    drawLine(
                        color = Ink,
                        start = Offset(size.width * .16f, size.height * y),
                        end = Offset(size.width * .84f, size.height * y),
                        strokeWidth = stroke,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.height(if (extraCompact) 50.dp else 54.dp),
            shape = RoundedCornerShape(if (extraCompact) 25.dp else 27.dp),
            color = Color.White.copy(alpha = .94f),
            border = BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(if (extraCompact) 40.dp else 44.dp)
                        .pressClickable(style = PressScaleStyle.Button, onClick = onNew)
                        .clip(CircleShape).background(Ink)
                        .semantics { contentDescription = newChatDescription; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { Text("+", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light) }
                Box {
                    Box(
                        Modifier.size(width = if (extraCompact) 44.dp else 48.dp, height = if (extraCompact) 40.dp else 44.dp)
                            .pressClickable(enabled = exportEnabled, style = PressScaleStyle.Button) { exportExpanded = true }
                            .clip(CircleShape)
                            .semantics { contentDescription = exportDescription; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) { ExportMark() }
                    ExportOptionsBubble(
                        expanded = exportExpanded,
                        placement = BubblePlacement.Below,
                        onDismissRequest = { exportExpanded = false },
                        onSave = {
                            exportExpanded = false
                            onExportSave()
                        },
                        onShare = {
                            exportExpanded = false
                            onExportShare()
                        },
                    )
                }
            }
        }
    }
    }
}

@Composable
internal fun ExportMark() {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .09f
        drawLine(Ink, Offset(size.width * .5f, size.height * .08f), Offset(size.width * .5f, size.height * .66f), stroke)
        drawLine(Ink, Offset(size.width * .27f, size.height * .31f), Offset(size.width * .5f, size.height * .08f), stroke)
        drawLine(Ink, Offset(size.width * .73f, size.height * .31f), Offset(size.width * .5f, size.height * .08f), stroke)
        drawLine(Ink, Offset(size.width * .16f, size.height * .6f), Offset(size.width * .16f, size.height * .9f), stroke)
        drawLine(Ink, Offset(size.width * .84f, size.height * .6f), Offset(size.width * .84f, size.height * .9f), stroke)
        drawLine(Ink, Offset(size.width * .16f, size.height * .9f), Offset(size.width * .84f, size.height * .9f), stroke)
    }
}

@Composable
internal fun QuietButton(symbol: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(KcodeSize.compactControl)
            .pressScale(interaction, PressScaleStyle.Button)
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = SoftInk, fontSize = 17.sp) }
}
