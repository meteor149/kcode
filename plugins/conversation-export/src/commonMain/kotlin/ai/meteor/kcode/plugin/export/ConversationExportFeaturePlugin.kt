package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.export.ui.ConversationExportUiPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Owns rendering, saving and export orchestration as one feature. */
object ConversationExportFeaturePlugin : Plugin<Any?> {
    override val name = "feature.conversation-export"
    override val config = ConfigValidator<Any?> {
        require(it == Unit || it is ConversationImageSaverFactory) {
            "Conversation export requires Unit or a saving factory"
        }
        it
    }

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val factory = if (config == Unit) nativeConversationImageSaverFactory(ctx, effect)
            else config as ConversationImageSaverFactory
        val saving = ctx.plugin(ConversationImageSavingProviderPlugin, factory)
        effect.collect { saving.dispose() }
        saving.await()
        listOf(ConversationImageRenderingPlugin, ConversationExportPlugin).forEach { child ->
            val fiber = ctx.plugin(child, Unit)
            effect.collect { fiber.dispose() }
        }
        val ui = ctx.plugin(ConversationExportUiPlugin, Unit)
        effect.collect { ui.dispose() }
    }
}

internal expect fun nativeConversationImageSaverFactory(ctx: Context, effect: EffectScope): ConversationImageSaverFactory
