package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import ai.meteor.kcode.skill.skillTools
import org.cordis.dependencies
import org.cordis.plugin

fun skillToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.skill", "builtin", "built-in", setOf("skill", "tools")),
    plugin<Unit>(name = "kcode-skill-consumer", inject = dependencies(KcodeTools.Key, KcodeSkills.Key)) { ctx, _ ->
        val runtime = ctx.require(KcodeSkills.Key).runtime
        val tools = ToolRegistry { if (runtime != null) skillTools(runtime) }
        collect(ctx.require(KcodeTools.Key).register("consumer.tools.skill", tools))
    },
    Unit,
)

fun skillToolPlugin(tools: ToolRegistry): KcodePluginMount = kcodePlugin(
    descriptor = PluginDescriptor(
        id = "consumer.tools.skill",
        version = "builtin",
        source = "built-in",
        capabilities = setOf("skill", "tools"),
    ),
    plugin = toolContributionPlugin("consumer.tools.skill", tools),
    config = Unit,
)
