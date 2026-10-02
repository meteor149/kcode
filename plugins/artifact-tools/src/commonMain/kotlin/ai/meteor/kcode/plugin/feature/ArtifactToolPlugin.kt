package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifactTools
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.toolContributionPlugin
import org.cordis.dependencies
import org.cordis.plugin

/** Rebinds the contribution whenever the artifacts provider changes. */
fun artifactToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.artifact", "builtin", "built-in", setOf("artifact", "tools")),
    plugin<Unit>(name = "kcode-artifact-consumer", inject = dependencies(KcodeTools.Key, KcodeArtifacts.Key)) { ctx, _ ->
        val repository = ctx.require(KcodeArtifacts.Key).repository
        val tools = ToolRegistry { if (repository is MutableArtifactRepository) artifactTools(repository) }
        collect(ctx.require(KcodeTools.Key).register("consumer.tools.artifact", tools))
    },
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
