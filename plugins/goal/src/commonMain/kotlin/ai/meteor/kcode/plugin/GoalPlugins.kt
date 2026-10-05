package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.goal.HistoryGoalSessions
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.goalTools
import ai.meteor.kcode.plugin.api.ContinuationPolicy
import ai.meteor.kcode.plugin.api.KcodeContinuations
import ai.meteor.kcode.plugin.api.KcodeTools
import org.cordis.Disposable
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object GoalToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-tool-goal"
    override val inject: Dependencies = dependencies(KcodeTools.Key, KcodeGoals.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("core/goal") { it.goalSession?.let(::goalTools) ?: ToolRegistry { } })
    }
}

object GoalContinuationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-goal-round-driver"
    override val inject: Dependencies = dependencies(KcodeContinuations.Key, KcodeGoals.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeContinuations.Key).register(
                ContinuationPolicy("goal", order = 100) { it.goalContinuation() },
            ),
        )
    }
}

/** Session instance and mutex are shared by all consumers of the same conversation. */
object GoalSessionProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-goal-sessions"
    override val inject = dependencies(KcodeHistory.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sessions = HistoryGoalSessions(ctx.require(KcodeHistory.Key).repository)
        effect.collect(Disposable { sessions.close() })
        KcodeGoals(ctx, sessions)
    }
}
