package ai.meteor.kcode.plugin.pages.chat

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.ui.component.FloatingCircleButton
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.QuietButton
import ai.meteor.kcode.ui.component.kcodeGlassEffect
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
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
                    Box(Modifier.matchParentSize().kcodeGlassEffect(hazeState, RoundedCornerShape(27.dp)))
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
                            FloatingCircleButton(
                                description = text(UiText.NewChat),
                                onClick = onNew,
                                size = if (extraCompact) 40.dp else 44.dp,
                                containerColor = MaterialTheme.colorScheme.inverseSurface,
                            ) {
                                KcodeIcon(KcodeIconAsset.Add, MaterialTheme.colorScheme.inverseOnSurface, Modifier.size(24.dp))
                            }
                        }
                        actions()
                    }
                }
            }
        }
    }
}
