package ai.meteor.kcode.plugin.provider

import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.api.KcodeWebSearch
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.searchhttp.HttpWebSearchBackend
import ai.meteor.kcode.tools.search.WebSearchBackend
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ai.meteor.kcode.plugin.searchhttp.httpSearchConfiguration
import org.cordis.Disposable
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object HttpWebSearchProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-search-http"
    override val inject = dependencies(KcodeSettings.Key, KcodeSearchSettings.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val settings = ctx.require(KcodeSettings.Key).store
        val searchPolicy = ctx.require(KcodeSearchSettings.Key).policy
        val backend = HttpWebSearchBackend(configurationProvider = {
            searchPolicy.resolve(settings.load()).httpSearchConfiguration()
        })
        effect.collect(Disposable {
            owner.requireCanClose()
            withContext(NonCancellable) {
                try { owner.close() } finally { backend.closeAndJoin() }
            }
        })
        KcodeWebSearch(ctx, object : WebSearchBackend {
            override suspend fun search(query: String, maxResults: Int) =
                owner.run { backend.search(query, maxResults) }
        })
    }
}

fun webSearchProviderPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.web.search-http", "builtin", "built-in", setOf("web")),
    HttpWebSearchProviderPlugin,
    Unit,
)
