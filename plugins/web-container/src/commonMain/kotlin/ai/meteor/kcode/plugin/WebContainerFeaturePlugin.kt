package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.feature.WebContainerToolConsumerPlugin
import ai.meteor.kcode.webcontainer.WebContainerController
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Callback-configured composition owns the same consumers as native deployments. */
object WebContainerFeaturePlugin : Plugin<WebContainerController?> {
    override val name = "feature.web-container"

    override suspend fun apply(ctx: Context, config: WebContainerController?, effect: EffectScope) {
        val provider = ctx.plugin(WebContainersProviderPlugin, config)
        effect.collect { provider.dispose() }
        provider.await()
        mountWebContainerConsumers(ctx, effect)
    }
}

internal suspend fun mountWebContainerConsumers(ctx: Context, effect: EffectScope) {
    listOf(WebContainerToolConsumerPlugin, WebContainersUiPlugin).forEach { child ->
        val fiber = ctx.plugin(child, Unit)
        effect.collect { fiber.dispose() }
    }
}
