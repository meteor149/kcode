package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner
import ai.meteor.kcode.plugin.api.KcodeExecution
import ai.meteor.kcode.plugin.api.KcodeGeneration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class GenerationProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-generation-provider"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val job = SupervisorJob()
        val runner = OwnedChatGenerationRunner(scope = CoroutineScope(job + Dispatchers.Main.immediate), admission = ctx.root[KcodeExecution.Key]?.admission)
        effect.collect {
            runner.requireCanClose()
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
        KcodeGeneration(ctx, runner)
    }
}
