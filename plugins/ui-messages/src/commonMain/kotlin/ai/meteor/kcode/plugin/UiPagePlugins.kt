package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import ai.meteor.kcode.plugin.uitexts.uimessages.BuiltinUiTexts
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

object DefaultConversationTranscriptUiPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "provider.ui.conversation.transcript"
    override val inject = dependencies(KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeUiSlots.Key).registerTexts(UiTextDictionary(name, BuiltinUiTexts)))
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.ConversationTranscript, DefaultConversationTranscriptRenderer))
    }
}
