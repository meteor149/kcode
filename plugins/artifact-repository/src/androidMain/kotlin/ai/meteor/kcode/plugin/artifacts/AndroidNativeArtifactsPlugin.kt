package ai.meteor.kcode.plugin.artifacts

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Allocates and releases native storage for each activation. */
class AndroidNativeArtifactsPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-native-artifacts"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
            "Android artifacts requires native host inputs"
        }
        FactoryFileArtifactsProviderPlugin.apply(ctx, androidArtifactFileStoreFactory(inputs.applicationContext()), effect)
    }
}
