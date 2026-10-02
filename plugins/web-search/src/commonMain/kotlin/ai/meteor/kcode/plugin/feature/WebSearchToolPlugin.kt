package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import ai.meteor.kcode.tools.search.WebSearchConfiguration
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.tools.search.WebSearchTool
import org.cordis.dependencies
import org.cordis.plugin

fun webSearchToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.web-search", "builtin", "built-in", setOf("web", "tools")),
    plugin<Unit>(name = "kcode-web-search-consumer", inject = dependencies(KcodeTools.Key, KcodeSettings.Key)) { ctx, _ ->
        val settings = ctx.require(KcodeSettings.Key).store
        val tools = ToolRegistry {
            tool(WebSearchTool(configurationProvider = {
                settings.load().let {
                    WebSearchConfiguration(
                        provider = WebSearchProvider.fromCode(it.webSearchProvider),
                        brightDataApiKey = it.webSearchApiKey,
                        exaApiKey = it.exaSearchApiKey,
                    )
                }
            }))
        }
        collect(ctx.require(KcodeTools.Key).register("consumer.tools.web-search", tools))
    },
    Unit,
)

fun webSearchToolPlugin(tools: ToolRegistry): KcodePluginMount = kcodePlugin(
    descriptor = PluginDescriptor(
        id = "consumer.tools.web-search",
        version = "builtin",
        source = "built-in",
        capabilities = setOf("web", "tools"),
    ),
    plugin = toolContributionPlugin("consumer.tools.web-search", tools),
    config = Unit,
)
