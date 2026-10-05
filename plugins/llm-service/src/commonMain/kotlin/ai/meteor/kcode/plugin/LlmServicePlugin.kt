package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeLlm
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object LlmServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-llm"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeLlm(ctx)
    }
}
