package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.toolContributionPlugin

fun filesystemToolPlugin(tools: ToolRegistry): KcodePluginMount = kcodePlugin(
    descriptor = PluginDescriptor(
        id = "consumer.tools.filesystem",
        version = "builtin",
        source = "built-in",
        capabilities = setOf("fs", "tools"),
    ),
    plugin = toolContributionPlugin("consumer.tools.filesystem", tools),
    config = Unit,
)
