package ai.meteor.kcode.plugin

import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.ToolPermissionSettingsPolicy
import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationContent
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPosition
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import ai.meteor.kcode.plugin.uitexts.interaction.BuiltinUiTexts
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.ui.component.AnchoredBubblePopup
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PopupChoiceRow
import ai.meteor.kcode.ui.component.PopupNavigationRow
import ai.meteor.kcode.ui.component.PopupSectionLabel
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Private optional UI child; headless policy remains usable without default UI services. */
internal object ToolPermissionUiPlugin : Plugin<Unit> {
    override val name = "tool-permission-ui"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeUiSlots.Key, KcodeInteraction.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeInteraction.Key).policy.settings ?: return
        val slots = ctx.require(KcodeUiSlots.Key)
        val active = MutableStateFlow(true)
        val texts = slots.registerTexts(UiTextDictionary(name, BuiltinUiTexts))
        val registration = try {
            slots.registerConversationDecoration(ConversationDecoration(
                "tool-permission-controls", 100, PermissionPresenter(policy, active),
            ))
        } catch (error: Throwable) {
            active.value = false
            texts.dispose()
            throw error
        }
        effect.collect {
            active.value = false
            try { registration.dispose() } finally { texts.dispose() }
        }
    }
}

private class PermissionPresenter(
    private val policy: ToolPermissionSettingsPolicy,
    private val active: MutableStateFlow<Boolean>,
) : ConversationDecorationPresenter {
    @Composable
    override fun Present(context: ConversationPageContext): List<ConversationDecorationContent> {
        if (!active.collectAsState().value || context.settingsEditor == null) return emptyList()
        val current by rememberUpdatedState(context)
        return listOf(ConversationDecorationContent(
            0.dp,
            UiRenderer {
                if (active.collectAsState().value) current.settingsEditor?.let { editor ->
                    Spacer(Modifier.width(KcodeSpacing.hair))
                    ToolPermissionModeButton(
                        policy.resolve(editor.draft), KcodeSize.compactControl,
                        onModeChange = { mode ->
                            check(active.value) { "Tool permission controls have been disposed" }
                            current.settingsEditor?.let { latest -> latest.submit(policy.update(latest.draft, mode)) }
                        },
                        hazeState = current.hazeState,
                    )
                }
            },
            ConversationDecorationPosition.ComposerActions,
        ))
    }
}

@Composable
private fun ToolPermissionModeButton(
    mode: ToolPermissionMode,
    size: Dp,
    onModeChange: (ToolPermissionMode) -> Unit,
    hazeState: KcodeHazeState?,
) {
    var expanded by remember { mutableStateOf(false) }
    val modeName = mode.code
    val description = "${text(UiText.ToolPermissionMode)}: $modeName"
    Box {
        Column(
            modifier = Modifier.width(KcodeSize.compactPermissionControl).height(size)
                .pressClickable(style = PressScaleStyle.Button) { expanded = true }
                .clip(RoundedCornerShape(KcodeRadius.control))
                .semantics { contentDescription = description; role = Role.Button }
                .padding(horizontal = KcodeSpacing.hair),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text(UiText.ToolPermissionShort),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                modeName,
                modifier = Modifier.padding(top = KcodeSpacing.hair / 2),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        AnchoredBubblePopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            placement = BubblePlacement.Above,
            hazeState = hazeState,
            shape = MaterialTheme.shapes.extraLarge,
            surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f),
            minWidth = 232.dp,
            maxWidth = 272.dp,
            maxHeight = 440.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.sm)) {
                PopupNavigationRow(label = text(UiText.ToolPermissionMode), value = modeName)
                HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = MaterialTheme.colorScheme.outlineVariant, thickness = .7.dp)
                PopupSectionLabel(text(UiText.PermissionLevel))
                ToolPermissionMode.entries.forEach { item ->
                    val itemDescription = when (item) {
                        ToolPermissionMode.Deny -> text(UiText.ToolPermissionDenyDescription)
                        ToolPermissionMode.Ask -> text(UiText.ToolPermissionAskDescription)
                        ToolPermissionMode.Bypass -> text(UiText.ToolPermissionBypassDescription)
                    }
                    PopupChoiceRow(
                        title = item.code,
                        subtitle = itemDescription,
                        selected = item == mode,
                        titleColor = if (item == ToolPermissionMode.Bypass) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            expanded = false
                            onModeChange(item)
                        },
                    )
                }
            }
        }
    }
}
