package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.meteor.kcode.plugin.filesystem.ReadMediaFileTool
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.kcodePlugin
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object FilesystemToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-filesystem-tools"
    override val inject = dependencies(KcodeTools.Key, KcodeFileSystem.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val backend = ToolFileSystem(ctx.require(KcodeFileSystem.Key).backend)
        val tools = ToolRegistry {
            tool(ReadFileTool(backend))
            tool(ListDirectoryTool(backend))
            tool(WriteFileTool(backend))
            tool(EditFileTool(backend))
            tool(ReadMediaFileTool(backend))
        }
        effect.collect(ctx.require(KcodeTools.Key).register("consumer.tools.filesystem", tools))
    }
}

/** Model schemas are consumers of fs; replacing the backend rebuilds this contribution. */
fun filesystemToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.filesystem", "builtin", "built-in", setOf("fs", "tools")),
    FilesystemToolConsumerPlugin,
    Unit,
)
