package ai.meteor.kcode.plugin.localization

import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** One deployment boundary for dictionaries and their optional default UI consumers. */
object LocalizationFeaturePlugin : Plugin<Any?> {
    override val name = "feature.localization"
    override val config = ConfigValidator<Any?> { resolveDictionaryConfiguration(it) }

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val provider = ctx.plugin(LocalizationProviderPlugin, config)
        effect.collect { provider.dispose() }
        provider.await()
        val projection = ctx.plugin(LocalizationUiContributionPlugin, Unit)
        effect.collect { projection.dispose() }
    }
}
