package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** The built-in and external APK entry share the same leased native implementation. */
class AndroidNativeImageSavingPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-native-image-saving"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android image saving requires native host inputs"
        }
        ConversationImageSavingProviderPlugin.apply(ctx, androidConversationImageSaverFactory(inputs), effect)
    }
}
