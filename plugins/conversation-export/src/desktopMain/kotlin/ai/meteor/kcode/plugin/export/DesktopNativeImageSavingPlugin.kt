package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class DesktopNativeImageSavingPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "desktop-native-image-saving"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? DesktopPluginHostInputs) {
            "Desktop image saving requires native host inputs"
        }
        ConversationImageSavingProviderPlugin.apply(ctx, desktopConversationImageSaverFactory(inputs), effect)
    }
}
