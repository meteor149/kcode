package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ContinuationPolicy
import ai.meteor.kcode.plugin.api.KcodeContinuations
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.subagentTools
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object SubagentToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-tool-subagent"
    override val inject: Dependencies = dependencies(KcodeTools.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("core/subagent") { subagentTools(it.coordinator, it.agentPath) })
    }
}

object SubagentContinuationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-subagent-continuation"
    override val inject: Dependencies = dependencies(KcodeContinuations.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeContinuations.Key).register(
                ContinuationPolicy("subagent", order = 0) { it.subagentContinuation() },
            ),
        )
    }
}
