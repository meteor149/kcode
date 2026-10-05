package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.contributions.OwnedUiContributions
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Neutral contributions for any application root; no default page vocabulary is required. */
object UiContributionsServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-ui-contributions"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val contributions = OwnedUiContributions(ctx)
        effect.collect { contributions.close() }
    }
}
