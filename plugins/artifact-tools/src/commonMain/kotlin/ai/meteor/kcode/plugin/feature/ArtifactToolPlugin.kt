package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.plugin.artifacttools.artifactTools
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object ArtifactToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-artifact-consumer"
    override val inject = dependencies(KcodeTools.Key, KcodeArtifacts.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val repository = ctx.require(KcodeArtifacts.Key).repository
        val tools = ToolRegistry { if (repository is MutableArtifactRepository) artifactTools(repository) }
        effect.collect(ctx.require(KcodeTools.Key).register("consumer.tools.artifact", tools))
    }
}

/** Rebinds the contribution whenever the artifacts provider changes. */
fun artifactToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.artifact", "builtin", "built-in", setOf("artifact", "tools")),
    ArtifactToolConsumerPlugin,
    Unit,
)

fun artifactToolPlugin(tools: ToolRegistry): KcodePluginMount = kcodePlugin(
    descriptor = PluginDescriptor(
        id = "consumer.tools.artifact",
        version = "builtin",
        source = "built-in",
        capabilities = setOf("artifact", "tools"),
    ),
    plugin = toolContributionPlugin("consumer.tools.artifact", tools),
    config = Unit,
)
