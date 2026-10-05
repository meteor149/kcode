package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.subagentui.SubagentDecorationPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Owns the coordinator, model tools, continuation and optional presentation as one feature. */
object SubagentFeaturePlugin : Plugin<Any?> {
    override val config = ConfigValidator<Any?> { resolveConcurrencyLimit(it) }
    override val name = "feature.subagents"

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val provider = ctx.plugin(InProcessSubagentProviderPlugin, config)
        effect.collect { provider.dispose() }
        provider.await()
        listOf(
            SubagentToolConsumerPlugin,
            SubagentContinuationPlugin,
            SubagentDecorationPlugin,
        ).forEach { child ->
            val fiber = ctx.plugin(child, Unit)
            effect.collect { fiber.dispose() }
        }
    }
}
