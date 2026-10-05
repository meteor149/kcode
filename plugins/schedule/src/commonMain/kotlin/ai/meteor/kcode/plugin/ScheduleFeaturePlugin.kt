package ai.meteor.kcode.plugin

import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** One deployment boundary for scheduling, model tools and headless execution. */
object ScheduleFeaturePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "feature.schedule"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        listOf(ScheduledTaskProviderPlugin, ScheduledTaskToolConsumerPlugin, ScheduleDispatchPlugin).forEach { child ->
            val fiber = ctx.plugin(child, Unit)
            effect.collect { fiber.dispose() }
        }
    }
}
