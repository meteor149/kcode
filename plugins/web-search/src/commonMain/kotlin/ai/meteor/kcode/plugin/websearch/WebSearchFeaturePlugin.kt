package ai.meteor.kcode.plugin.websearch

import ai.meteor.kcode.plugin.feature.WebSearchToolConsumerPlugin
import ai.meteor.kcode.plugin.provider.HttpWebSearchProviderPlugin
import ai.meteor.kcode.plugin.provider.SearchSettingsProviderPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** One install/enable boundary for search, its configuration and optional UI/tool consumers. */
object WebSearchFeaturePlugin : Plugin<Unit> {
    override val name = "feature.web-search"
    override val config = ConfigValidator<Unit> { it }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val settings = ctx.plugin(SearchSettingsProviderPlugin, Unit)
        effect.collect { settings.dispose() }
        val backend = ctx.plugin(HttpWebSearchProviderPlugin, Unit)
        effect.collect { backend.dispose() }
        val tools = ctx.plugin(WebSearchToolConsumerPlugin, Unit)
        effect.collect { tools.dispose() }
    }
}
