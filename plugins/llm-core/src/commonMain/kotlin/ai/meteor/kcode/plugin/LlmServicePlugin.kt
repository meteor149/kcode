package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.llmcore.OwnedLlmRegistry
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object LlmServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-llm"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val registry = OwnedLlmRegistry(ctx)
        effect.collect { registry.close() }
    }
}
