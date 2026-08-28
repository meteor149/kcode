package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAgentModelRuntime
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.ModelAdapter
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object LlmServicePlugin : Plugin<Unit> {
    override val name = "kcode-llm"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeLlm(ctx)
    }
}

object DefaultModelAdaptersPlugin : Plugin<Unit> {
    override val name = "kcode-llm-koog"
    override val inject: Dependencies = dependencies(KcodeLlm.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeLlm.Key).register(
                ModelAdapter(
                    id = "koog-built-in-providers",
                    supports = { true },
                    create = ::createAgentModelRuntime,
                ),
            ),
        )
    }
}
