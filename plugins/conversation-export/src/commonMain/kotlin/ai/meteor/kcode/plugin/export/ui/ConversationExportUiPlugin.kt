package ai.meteor.kcode.plugin.export.ui

import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ExportAction
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeConversationExport
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationContent
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPosition
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import ai.meteor.kcode.plugin.uitexts.conversationexport.BuiltinUiTexts
import ai.meteor.kcode.ui.component.AnchoredBubblePopup
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.FloatingCircleButton
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PopupChoiceRow
import ai.meteor.kcode.ui.component.PopupNavigationRow
import ai.meteor.kcode.ui.component.PopupSectionLabel
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Hairline
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** A reactive child: UI withdrawal does not remove headless export services. */
internal object ConversationExportUiPlugin : Plugin<Unit> {
    override val name = "conversation-export-ui"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeUiSlots.Key, KcodeConversationExport.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val active = MutableStateFlow(true)
        val slots = ctx.require(KcodeUiSlots.Key)
        val texts = slots.registerTexts(UiTextDictionary(name, BuiltinUiTexts))
        val registration = try {
            slots.registerConversationDecoration(ConversationDecoration(
                "conversation-export", 300,
                ExportPresenter(ctx.require(KcodeConversationExport.Key).exporter, owner, active),
            ))
        } catch (error: Throwable) {
            try { owner.close() } finally { texts.dispose() }
            throw error
        }
        effect.collect {
            try { owner.close() } finally {
                active.value = false
                try { registration.dispose() } finally { texts.dispose() }
            }
        }
    }
}

private class ExportPresenter(
    private val exporter: ConversationExporter,
    private val owner: PluginOperationOwner,
    private val active: MutableStateFlow<Boolean>,
) : ConversationDecorationPresenter {
    @Composable
    override fun Present(context: ConversationPageContext): List<ConversationDecorationContent> {
        if (!active.collectAsState().value) return emptyList()
        val state = rememberChatExportState(exporter, owner)
        val current by rememberUpdatedState(context)
        val content = mutableListOf(ConversationDecorationContent(
            0.dp,
            UiRenderer { modifier ->
                if (active.collectAsState().value) ExportActions(modifier, current, state, active)
            },
            if (context.selectedMessageIds == null) ConversationDecorationPosition.MoreActions
            else ConversationDecorationPosition.HeaderActions,
        ))
        state.notice?.let { notice ->
            content += ConversationDecorationContent(0.dp, UiRenderer { modifier ->
                if (active.collectAsState().value) Surface(
                    modifier.widthIn(max = 360.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(notice, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall)
                }
            }, ConversationDecorationPosition.AboveComposer)
        }
        return content
    }
}

@Composable
private fun ExportActions(
    modifier: Modifier,
    context: ConversationPageContext,
    state: ChatExportState,
    active: MutableStateFlow<Boolean>,
) {
    var expanded by remember(context.conversation?.id, context.selectedMessageIds != null) { mutableStateOf(false) }
    val selectedIds = context.selectedMessageIds
    val enabled = !state.exporting && context.conversation?.messages.orEmpty().any {
        selectedIds == null || it.id in selectedIds
    }
    val description = text(if (context.selectedMessageIds == null) UiText.ExportConversation else UiText.ShareImage)
    fun dispatch(action: ExportAction) {
        if (!active.value) return
        expanded = false
        context.moreMenu?.dismiss()
        context.beforeAction()
        val selected = context.selectedMessageIds?.toSet()
        state.export(action, context.conversation, context.configuration, selected)
        if (selected != null) context.clearSelection()
    }
    @Composable
    fun Options() {
        PopupNavigationRow(label = text(UiText.ExportConversation))
        HorizontalDivider(
            Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair),
            color = Hairline, thickness = .7.dp,
        )
        PopupSectionLabel(text(UiText.ExportAction))
        PopupChoiceRow(title = text(UiText.SaveToPhotos), showChevron = true, onClick = { if (canExport(context, state)) dispatch(ExportAction.Save) })
        PopupChoiceRow(title = text(UiText.ShareImage), showChevron = true, onClick = { if (canExport(context, state)) dispatch(ExportAction.Share) })
    }
    if (context.selectedMessageIds == null) {
        PopupChoiceRow(title = text(UiText.Export), showChevron = true, onClick = {
            if (active.value) context.moreMenu?.showPage(UiRenderer { _ ->
                if (active.collectAsState().value) Options()
            })
        })
        return
    }
    Box(modifier) {
        FloatingCircleButton(description = description, onClick = { if (enabled) expanded = true }, size = 42.dp) {
            KcodeIcon(KcodeIconAsset.Share, MaterialTheme.colorScheme.onSurface, Modifier.size(21.dp))
        }
        AnchoredBubblePopup(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            placement = BubblePlacement.Below,
            minWidth = 216.dp,
            maxWidth = 256.dp,
            maxHeight = 320.dp,
            hazeState = context.hazeState,
            shape = MaterialTheme.shapes.extraLarge,
            surfaceColor = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
        ) {
            Options()
        }
    }
}

private fun canExport(context: ConversationPageContext, state: ChatExportState): Boolean =
    !state.exporting && context.conversation?.messages.orEmpty().any {
        context.selectedMessageIds == null || it.id in context.selectedMessageIds.orEmpty()
    }
