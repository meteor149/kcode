package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ContinuationPolicy
import ai.meteor.kcode.plugin.api.KcodeContinuations
import ai.meteor.kcode.plugin.api.KcodeTools
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object GoalToolConsumerPlugin : Plugin<Unit> {
    override val name = "kcode-tool-goal"
    override val inject: Dependencies = dependencies(KcodeTools.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("core/goal") { it.goalTools })
    }
}

object GoalContinuationPlugin : Plugin<Unit> {
    override val name = "kcode-goal-round-driver"
    override val inject: Dependencies = dependencies(KcodeContinuations.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeContinuations.Key).register(
                ContinuationPolicy("goal", order = 100) { it.goalContinuation() },
            ),
        )
    }
}
