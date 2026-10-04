package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeWebSearch
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.websearch.WebSearchTool
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object WebSearchToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-web-search-consumer"
    override val inject = dependencies(KcodeTools.Key, KcodeWebSearch.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val tools = ToolRegistry { tool(WebSearchTool(ctx.require(KcodeWebSearch.Key).backend)) }
        effect.collect(ctx.require(KcodeTools.Key).register("consumer.tools.web-search", tools))
    }
}

fun webSearchToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.web-search", "builtin", "built-in", setOf("web", "tools")),
    WebSearchToolConsumerPlugin,
    Unit,
)
