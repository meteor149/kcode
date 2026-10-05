package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.ModelAdapter
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Private build support: every provider owns its metadata and client factory. */
abstract class NativeModelAdapterPlugin(val provider: ModelProvider) : Plugin<Unit> {
    final override val config = ConfigValidator<Unit> { it }
    final override val name = "kcode-llm-koog.${provider.name}"
    final override val inject = dependencies(KcodeLlm.Key)
    protected abstract fun createAdapter(): ModelAdapter
    final override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeLlm.Key).register(createAdapter()))
    }
}
