package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Allocates and releases native storage for each activation. */
class AndroidNativeSettingsPlugin : Plugin<Any?> {
    override val config = ConfigValidator<Any?> { value ->
        require(value == Unit || value is String && value.matches(Regex("[a-zA-Z0-9._-]{1,160}"))) { "Invalid Android settings scope" }
        value
    }
    override val name = "android-native-settings"

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android settings requires native host inputs"
        }
        val factory = if (config is String) androidSettingsStoreFactory(inputs.applicationContext(), config)
            else androidSettingsStoreFactory(inputs.applicationContext())
        FactorySettingsProviderPlugin.apply(ctx, factory, effect)
    }
}
