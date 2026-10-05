package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.scheduledTaskCompletionTools
import ai.meteor.kcode.scheduledTaskTools
import ai.meteor.kcode.plugin.schedule.HistoryScheduledTaskCoordinator
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.KcodeHistory
import org.cordis.Disposable
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object ScheduledTaskToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-tool-schedule"
    override val inject: Dependencies = dependencies(KcodeTools.Key, KcodeSchedules.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeTools.Key).register("core/schedule") {
                (it.scheduledTaskSession?.let(::scheduledTaskTools) ?: ToolRegistry { }) +
                    (it.scheduledTaskCompletionSession?.let(::scheduledTaskCompletionTools) ?: ToolRegistry { })
            },
        )
    }
}

object ScheduledTaskProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-schedule-history"
    override val inject = dependencies(KcodeHistory.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val coordinator = HistoryScheduledTaskCoordinator(ctx.require(KcodeHistory.Key).repository)
        effect.collect(Disposable { coordinator.close() })
        KcodeSchedules(ctx, coordinator)
    }
}
