package ai.meteor.kcode.plugin.overlay

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.KcodeConversationOverlays
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Native presentation is allocated by each built-in or external APK generation. */
class AndroidNativeConversationOverlayPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-native-conversation-overlay"
    override val inject = dependencies(KcodeConversationOverlays.Key, KcodeLocalization.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android conversation overlays require native host inputs"
        }
        val registry = ctx.require(KcodeConversationOverlays.Key)
        ConversationOverlayProviderPlugin.apply(ctx, ConversationOverlayFactory {
            createAndroidConversationOverlayController(inputs.applicationContext(), registry.uiSlots)
        }, effect)
    }
}
