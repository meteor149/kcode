package ai.meteor.kcode.plugin.markdown

import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** One deployment boundary for formatting and its optional default UI projection. */
object MarkdownFeaturePlugin : Plugin<Unit> {
    override val name = "feature.markdown"
    override val config = ConfigValidator<Unit> { it }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val provider = ctx.plugin(MarkdownProviderPlugin, Unit)
        effect.collect { provider.dispose() }
        provider.await()
        val projection = ctx.plugin(MarkdownUiContributionPlugin, Unit)
        effect.collect { projection.dispose() }
    }
}
