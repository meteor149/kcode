package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.toolContributionPlugin

fun desktopShellToolPlugin(tools: ToolRegistry): KcodePluginMount = shellPlugin(
    id = "consumer.tools.shell",
    tools = tools,
)

fun androidShellToolPlugin(tools: ToolRegistry): KcodePluginMount = shellPlugin(
    id = "consumer.tools.android-shell",
    tools = tools,
)

fun ubuntuShellToolPlugin(tools: ToolRegistry): KcodePluginMount = shellPlugin(
    id = "consumer.tools.ubuntu-shell",
    tools = tools,
)

private fun shellPlugin(id: String, tools: ToolRegistry): KcodePluginMount = kcodePlugin(
    descriptor = PluginDescriptor(id, "builtin", "built-in", setOf("shell", "tools")),
    plugin = toolContributionPlugin(id, tools),
    config = Unit,
)
