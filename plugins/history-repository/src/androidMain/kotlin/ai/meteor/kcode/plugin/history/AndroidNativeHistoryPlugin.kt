package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Allocates and releases native storage for each activation. */
class AndroidNativeHistoryPlugin : Plugin<Any?> {
    override val config = ConfigValidator<Any?> { value ->
        require(value == Unit || value is String && java.io.File(value).isAbsolute) { "Invalid Android history path" }
        value
    }
    override val name = "android-native-history"

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android history requires native host inputs"
        }
        FactoryHistoryProviderPlugin.apply(ctx, androidHistoryRepositoryFactory(inputs.applicationContext(), config as? String), effect)
    }
}
