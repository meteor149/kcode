package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellResult
import java.nio.file.Files
import java.nio.file.Path
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** The workspace is deployment data; executor state belongs to each plugin activation. */
class DesktopNativeShellPlugin : Plugin<String> {
    override val name = "desktop-native-shell"
    override val config = ConfigValidator<String> { value ->
        require(value.isNotBlank()) { "Desktop Shell deployment path must not be blank" }
        require(Path.of(value).isAbsolute) { "Desktop Shell deployment path must be absolute" }
        value
    }


    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val workspace = Path.of(config)
        require(workspace.isAbsolute) { "Desktop shell workspace must be absolute" }
        val executor = DesktopShellCommandExecutor(Files.createDirectories(workspace).toRealPath())
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        KcodeShell(ctx, ShellBackend { request ->
            owner.run {
                val result = executor.execute(request.command, request.workingDirectory)
                ShellResult(result.output, result.exitCode)
            }
        })
    }
}
