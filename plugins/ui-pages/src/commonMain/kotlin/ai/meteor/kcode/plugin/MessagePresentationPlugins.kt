package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.ui.api.MessagePresentation
import ai.meteor.kcode.plugin.ui.api.ToolUsePresentation
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.pages.chat.component.AssistantMessageTimeline
import ai.meteor.kcode.plugin.pages.chat.component.ToolUseRow
import ai.meteor.kcode.plugin.pages.chat.component.UserMessageContent
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

fun messagePresentationPlugin(presentation: MessagePresentation): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.ui.message.${presentation.id}", "builtin", "built-in", setOf("uiSlots", "message.renderer")),
    plugin<Unit>(name = "message-${presentation.id}", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
        collect(ctx.require(KcodeUiSlots.Key).registerMessage(presentation))
    },
    Unit,
)

fun toolUsePresentationPlugin(presentation: ToolUsePresentation): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.ui.tool.${presentation.id}", "builtin", "built-in", setOf("uiSlots", "tool.renderer")),
    plugin<Unit>(name = "tool-${presentation.id}", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
        collect(ctx.require(KcodeUiSlots.Key).registerToolUse(presentation))
    },
    Unit,
)

object DefaultUserMessagePresentationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "message-user"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerMessage(
                MessagePresentation(
                    id = "user", order = 100,
                    supports = { it.role == MessageRole.User },
                    renderer = UiRenderer { UserMessageContent(it) },
                ),
            ),
        )
    }
}

object DefaultAssistantMessagePresentationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "message-assistant"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerMessage(
                MessagePresentation(
                    id = "assistant", order = 100,
                    supports = { it.role == MessageRole.Assistant && !it.isError },
                    renderer = UiRenderer { AssistantMessageTimeline(it.message, it.compact, it.onLongPressText) },
                ),
            ),
        )
    }
}

object DefaultErrorMessagePresentationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "message-error"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerMessage(
                MessagePresentation(
                    id = "error", order = 100,
                    supports = { it.role == MessageRole.Assistant && it.isError },
                    renderer = UiRenderer { AssistantMessageTimeline(it.message, it.compact, it.onLongPressText) },
                ),
            ),
        )
    }
}

object DefaultToolUsePresentationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "tool-default"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerToolUse(
                ToolUsePresentation(
                    id = "default", order = 1000,
                    supports = { true },
                    renderer = UiRenderer { ToolUseRow(it.toolUse, it.compact) },
                ),
            ),
        )
    }
}

fun defaultMessagePresentationPlugins(): List<KcodePluginMount> = listOf(
    kcodePlugin(PluginDescriptor("provider.ui.message.user", "builtin", "built-in", setOf("uiSlots", "message.renderer")), DefaultUserMessagePresentationPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.message.assistant", "builtin", "built-in", setOf("uiSlots", "message.renderer")), DefaultAssistantMessagePresentationPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.message.error", "builtin", "built-in", setOf("uiSlots", "message.renderer")), DefaultErrorMessagePresentationPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.tool.default", "builtin", "built-in", setOf("uiSlots", "tool.renderer")), DefaultToolUsePresentationPlugin, Unit),
)
