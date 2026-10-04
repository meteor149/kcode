package ai.meteor.kcode.plugin.feature

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.plugin.shell.AgentShellTool
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.kcodePlugin
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object DesktopShellToolConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "consumer.tools.shell"
    override val inject = dependencies(KcodeTools.Key, KcodeShell.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val backend = ctx.require(KcodeShell.Key).executor
        val tools = ToolRegistry { tool(AgentShellTool(ToolShellExecutor(backend))) }
        effect.collect(ctx.require(KcodeTools.Key).register(name, tools))
    }
}

object AndroidShellToolConsumerPlugin : Plugin<String> {
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Android shell tool description must not be blank" }
        value
    }
    override val name = "consumer.tools.android-shell"
    override val inject = dependencies(KcodeTools.Key, KcodeShell.Key)

    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val backend = ctx.require(KcodeShell.Key).executor
        val tools = ToolRegistry { tool(AgentShellTool(ToolShellExecutor(backend), description = config)) }
        effect.collect(ctx.require(KcodeTools.Key).register(name, tools))
    }
}

object UbuntuShellToolConsumerPlugin : Plugin<String> {
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Ubuntu shell tool description must not be blank" }
        value
    }
    override val name = "kcode-ubuntu-shell-tools"
    override val inject = dependencies(KcodeTools.Key, KcodeUbuntuShell.Key)

    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val backend = ctx.require(KcodeUbuntuShell.Key).executor
        val tools = ToolRegistry { tool(AgentShellTool(ToolShellExecutor(backend), "execute_ubuntu_command", config)) }
        effect.collect(ctx.require(KcodeTools.Key).register("consumer.tools.ubuntu-shell", tools))
    }
}

fun desktopShellToolPlugin(): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.shell", "builtin", "built-in", setOf("shell", "tools")),
    DesktopShellToolConsumerPlugin,
    Unit,
)

fun androidShellToolPlugin(description: String = AndroidShellToolDescription): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.android-shell", "builtin", "built-in", setOf("shell", "tools")),
    AndroidShellToolConsumerPlugin,
    description,
)

fun ubuntuShellToolPlugin(description: String = AndroidUbuntuShellToolDescription): KcodePluginMount = kcodePlugin(
    PluginDescriptor("consumer.tools.ubuntu-shell", "builtin", "built-in", setOf("ubuntuShell", "tools")),
    UbuntuShellToolConsumerPlugin,
    description,
)

private class ToolShellExecutor(private val backend: ShellBackend) : AgentShellExecutor {
    override suspend fun execute(command: String, workingDirectory: String?): AgentShellExecutor.ExecutionResult =
        backend.run(ShellRequest(command, workingDirectory)).let { AgentShellExecutor.ExecutionResult(it.output, it.exitCode) }
}
