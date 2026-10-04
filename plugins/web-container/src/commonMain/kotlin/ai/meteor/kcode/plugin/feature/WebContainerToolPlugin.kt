package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import ai.meteor.kcode.plugin.webcontainer.webContainerTools
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object WebContainerToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-web-container-consumer"
    override val inject = dependencies(KcodeTools.Key, KcodeWebContainers.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val controller = ctx.require(KcodeWebContainers.Key).controller
        val tools = ToolRegistry { if (controller != null) webContainerTools(controller) }
        effect.collect(ctx.require(KcodeTools.Key).register("consumer.tools.web-container", tools))
    }
}

fun webContainerToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.web-container", "builtin", "built-in", setOf("webContainer", "tools")),
    WebContainerToolConsumerPlugin,
    Unit,
)

fun webContainerToolPlugin(tools: ToolRegistry): KcodePluginMount = kcodePlugin(
    descriptor = PluginDescriptor(
        id = "consumer.tools.web-container",
        version = "builtin",
        source = "built-in",
        capabilities = setOf("webContainer", "tools"),
    ),
    plugin = toolContributionPlugin("consumer.tools.web-container", tools),
    config = Unit,
)
