package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.goalui.GoalDecorationPlugin
import ai.meteor.kcode.plugin.goalui.GoalRestorationEffectPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** One deployment and enable boundary for persisted goals and all of their contributions. */
object GoalFeaturePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "feature.goal"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        listOf(
            GoalSessionProviderPlugin,
            GoalCommandConsumerPlugin,
            GoalToolConsumerPlugin,
            GoalContinuationPlugin,
            GoalDecorationPlugin,
            GoalRestorationEffectPlugin,
        ).forEach { child ->
            val fiber = ctx.plugin(child, Unit)
            effect.collect { fiber.dispose() }
        }
    }
}
