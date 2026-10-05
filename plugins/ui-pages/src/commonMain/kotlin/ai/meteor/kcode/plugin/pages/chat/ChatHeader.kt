package ai.meteor.kcode.plugin.pages.chat

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.ui.api.ConversationMoreMenu
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.ui.component.FloatingCircleButton
import ai.meteor.kcode.ui.component.AnchoredBubblePopup
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PopupNavigationRow
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.QuietButton
import ai.meteor.kcode.ui.component.kcodeGlassEffect
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopChatHeader(
    title: String,
    selectionMode: Boolean,
    selectedCount: Int,
    configuration: ModelConfiguration?,
    onMenu: () -> Unit,
    moreMenu: ChatMoreMenuState,
    moreActions: @Composable () -> Unit,
    onCancelSelection: () -> Unit,
    actions: @Composable () -> Unit,
    onMissingConfiguration: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            FloatingCircleButton(description = text(UiText.Cancel), onClick = onCancelSelection, size = 42.dp) {
                KcodeIcon(KcodeIconAsset.Close, Ink, Modifier.size(20.dp))
            }
            Text(
                text(UiText.SelectedMessages, selectedCount),
                Modifier.padding(start = 12.dp).weight(1f),
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
            )
            actions()
        } else {
            QuietButton(KcodeIconAsset.Menu, text(UiText.OpenSidebar), onMenu)
            Text(
                title,
                Modifier.padding(start = 13.dp).weight(1f),
                color = SoftInk,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
            ChatMoreActions(compact = false, menu = moreMenu, actions = moreActions)
            ModelBadge(configuration, onMissingConfiguration, onConfigurationChange)
        }
    }
    HorizontalDivider(color = Hairline, thickness = 0.5.dp)
}

@Composable
internal fun CompactChatHeader(
    modifier: Modifier = Modifier,
    hazeState: KcodeHazeState,
    selectionMode: Boolean,
    selectedCount: Int,
    onCancelSelection: () -> Unit,
    actions: @Composable () -> Unit,
    onMenu: () -> Unit,
    onNew: () -> Unit,
    moreMenu: ChatMoreMenuState,
    moreActions: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val extraCompact = maxWidth < 360.dp
        Row(
            Modifier.fillMaxWidth().height(if (extraCompact) 72.dp else 80.dp)
                .padding(horizontal = if (extraCompact) 12.dp else 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingCircleButton(
                description = text(if (selectionMode) UiText.Cancel else UiText.OpenSidebar),
                onClick = if (selectionMode) onCancelSelection else onMenu,
                backgroundModifier = Modifier.kcodeGlassEffect(hazeState, CircleShape),
                size = if (extraCompact) 48.dp else 52.dp,
                containerColor = Color.Transparent,
                border = BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
            ) {
                KcodeIcon(if (selectionMode) KcodeIconAsset.Close else KcodeIconAsset.Menu, Ink, Modifier.size(22.dp))
            }
            Surface(
                modifier = Modifier.height(if (extraCompact) 50.dp else 54.dp),
                shape = RoundedCornerShape(if (extraCompact) 25.dp else 27.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
                shadowElevation = 8.dp,
            ) {
                Box {
                    Box(Modifier.matchParentSize().kcodeGlassEffect(hazeState, RoundedCornerShape(if (extraCompact) 25.dp else 27.dp)))
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            Text(
                                text(UiText.SelectedMessages, selectedCount),
                                Modifier.padding(horizontal = 8.dp),
                                color = Ink,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        } else {
                            val newChatDescription = text(UiText.NewChat)
                            Box(
                                Modifier.size(if (extraCompact) 40.dp else 44.dp)
                                    .pressClickable(style = PressScaleStyle.Button, onClick = onNew)
                                    .clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface)
                                    .semantics { contentDescription = newChatDescription; role = Role.Button },
                                contentAlignment = Alignment.Center,
                            ) {
                                KcodeIcon(KcodeIconAsset.Add, MaterialTheme.colorScheme.inverseOnSurface, Modifier.size(24.dp))
                            }
                        }
                        actions()
                        if (!selectionMode) ChatMoreActions(compact = true, menu = moreMenu, actions = moreActions, hazeState = hazeState, extraCompact = extraCompact)
                    }
                }
            }
        }
    }
}

internal class ChatMoreMenuState : ConversationMoreMenu {
    var expanded by mutableStateOf(false)
    var page by mutableStateOf<UiRenderer<Modifier>?>(null)
    fun open() { page = null; expanded = true }
    override fun showPage(renderer: UiRenderer<Modifier>) { if (expanded) page = renderer }
    override fun dismiss() { expanded = false; page = null }
}

@Composable
private fun ChatMoreActions(
    compact: Boolean,
    menu: ChatMoreMenuState,
    actions: @Composable () -> Unit,
    hazeState: KcodeHazeState? = null,
    extraCompact: Boolean = false,
) {
    val description = text(UiText.More)
    Box {
        if (compact) {
            Box(
                Modifier.size(width = if (extraCompact) 44.dp else 48.dp, height = if (extraCompact) 40.dp else 44.dp)
                    .pressClickable(style = PressScaleStyle.Button, onClick = menu::open)
                    .clip(CircleShape)
                    .semantics { contentDescription = description; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { KcodeIcon(KcodeIconAsset.More, Ink, Modifier.size(21.dp)) }
        } else {
            QuietButton(KcodeIconAsset.More, description, menu::open)
        }
        AnchoredBubblePopup(
            expanded = menu.expanded,
            onDismissRequest = menu::dismiss,
            placement = BubblePlacement.Below,
            minWidth = 216.dp,
            maxWidth = 256.dp,
            maxHeight = 320.dp,
            hazeState = hazeState,
            shape = MaterialTheme.shapes.extraLarge,
            surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
            borderColor = Hairline.copy(alpha = .72f),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.sm)) {
                val page = menu.page
                if (page == null) {
                    PopupNavigationRow(label = text(UiText.More))
                    HorizontalDivider(
                        Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair),
                        color = Hairline, thickness = .7.dp,
                    )
                    actions()
                } else page.Render(Modifier.fillMaxWidth())
            }
        }
    }
}
