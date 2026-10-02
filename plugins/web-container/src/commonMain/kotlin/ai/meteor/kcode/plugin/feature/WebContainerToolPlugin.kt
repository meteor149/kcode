package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import ai.meteor.kcode.webContainerTools
import org.cordis.dependencies
import org.cordis.plugin

fun webContainerToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.web-container", "builtin", "built-in", setOf("webContainer", "tools")),
    plugin<Unit>(name = "kcode-web-container-consumer", inject = dependencies(KcodeTools.Key, KcodeWebContainers.Key)) { ctx, _ ->
        val controller = ctx.require(KcodeWebContainers.Key).controller
        val tools = ToolRegistry { if (controller != null) webContainerTools(controller) }
        collect(ctx.require(KcodeTools.Key).register("consumer.tools.web-container", tools))
    },
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
