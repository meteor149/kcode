package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeContinuations
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object ContinuationServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-continuations"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeContinuations(ctx)
    }
}
