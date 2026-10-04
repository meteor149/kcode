package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeSubagents
import org.cordis.Disposable
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object InProcessSubagentProviderPlugin : Plugin<Any?> {
    override val config = ConfigValidator<Any?> { resolveConcurrencyLimit(it) }
    override val name = "kcode-subagent-in-process"
    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val factory = OwnedSubagentFactory((config as ConcurrencyLimit).value)
        effect.collect(Disposable { factory.close() })
        KcodeSubagents(ctx, factory)
    }
}
