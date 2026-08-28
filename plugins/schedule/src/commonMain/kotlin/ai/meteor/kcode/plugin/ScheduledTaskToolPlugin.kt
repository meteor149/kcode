package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeTools
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object ScheduledTaskToolConsumerPlugin : Plugin<Unit> {
    override val name = "kcode-tool-schedule"
    override val inject: Dependencies = dependencies(KcodeTools.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeTools.Key).register("core/schedule") {
                it.scheduledTaskTools + it.scheduledTaskCompletionTools
            },
        )
    }
}
