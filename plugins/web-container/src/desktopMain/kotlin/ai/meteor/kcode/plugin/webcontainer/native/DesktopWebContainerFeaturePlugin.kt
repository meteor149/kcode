package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.plugin.mountWebContainerConsumers
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class DesktopWebContainerFeaturePlugin : Plugin<String> {
    override val name = "feature.web-container"
    override val config = DesktopNativeWebContainerPlugin().config

    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val provider = ctx.plugin(DesktopNativeWebContainerPlugin(), config)
        effect.collect { provider.dispose() }
        provider.await()
        mountWebContainerConsumers(ctx, effect)
    }
}
