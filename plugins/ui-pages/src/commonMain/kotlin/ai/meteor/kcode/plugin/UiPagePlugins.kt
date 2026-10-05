package ai.meteor.kcode.plugin


import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.uitexts.uipages.BuiltinUiTexts
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import org.cordis.Dependencies
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

/** Provider implementations are separate contributions, each with reversible ownership. */
fun <T : Any> uiSlotPlugin(id: String, slot: UiSlotKey<T>, renderer: T, inject: Dependencies = dependencies(KcodeUiSlots.Key)): KcodePluginMount = kcodePlugin(
    PluginDescriptor(id, "builtin", "built-in", setOf("uiSlots", slot.id)),
    plugin<Unit>(name = id, inject = inject) { ctx, _ ->
        collect(ctx.require(KcodeUiSlots.Key).register(slot, renderer))
    },
    Unit,
)

object DefaultChatUiPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "provider.ui.chat"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeConversationExecution.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeUiSlots.Key).registerTexts(UiTextDictionary(name, BuiltinUiTexts)))
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.Chat, DefaultChatPageRenderer))
    }
}

object DefaultStandaloneConversationUiPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "provider.ui.conversation.standalone"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeSessions.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeUiSlots.Key).registerTexts(UiTextDictionary(name, BuiltinUiTexts)))
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.StandaloneConversation, DefaultStandaloneConversationRenderer))
    }
}

fun defaultUiPagePlugins(): List<KcodePluginMount> = listOf(
    kcodePlugin(PluginDescriptor("provider.ui.chat", "builtin", "built-in", setOf("uiSlots", ApplicationSlots.Chat.id)), DefaultChatUiPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.conversation.standalone", "builtin", "built-in", setOf("uiSlots", ApplicationSlots.StandaloneConversation.id)), DefaultStandaloneConversationUiPlugin, Unit),
)
