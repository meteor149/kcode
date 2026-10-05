package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import ai.meteor.kcode.plugin.skilltools.skillTools
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object SkillToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-skill-consumer"
    override val inject = dependencies(KcodeTools.Key, KcodeSkills.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val runtime = ctx.require(KcodeSkills.Key).runtime
        val tools = ToolRegistry { if (runtime != null) skillTools(runtime) }
        effect.collect(ctx.require(KcodeTools.Key).register("consumer.tools.skill", tools))
    }
}

fun skillToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.skill", "builtin", "built-in", setOf("skill", "tools")),
    SkillToolConsumerPlugin,
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
