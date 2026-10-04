package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodePluginInstallations
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object PluginInstallationsProviderPlugin : Plugin<PluginCompositionStore?> {
    override val name = "kcode-plugin-installations"
    override suspend fun apply(ctx: Context, config: PluginCompositionStore?, effect: EffectScope) {
        KcodePluginInstallations(ctx, config)
    }
}
