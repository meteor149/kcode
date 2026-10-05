package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.settings.ShellExecutionMode
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import java.nio.file.Files
import java.nio.file.Path

/** Portable entry consumes either settings policy or an SDK-only caller callback adapter. */
class AndroidPackagedShellPlugin : Plugin<Any?> {
    override val name = "android-packaged-shell"
    override val config = workspaceConfiguration()
    override val inject = dependencies(KcodeShellMode.Key)

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val policy = ctx.require(KcodeShellMode.Key).policy
        applyNativeShell(ctx, effect, ubuntu = false, workspace = config as? String) { policy.mode() }
    }
}

/** ARM64 deployment; both settings and caller callbacks supply the injected SDK policy. */
class AndroidPackagedUbuntuShellPlugin : Plugin<Any?> {
    override val name = "android-packaged-ubuntu-shell"
    override val config = workspaceConfiguration()
    override val inject = dependencies(KcodeShellMode.Key)

    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        val policy = ctx.require(KcodeShellMode.Key).policy
        applyNativeShell(ctx, effect, ubuntu = true, workspace = config as? String) { policy.mode() }
    }
}

/** Unit configuration; mode and deployment inputs resolve through the shared SDK. */
class AndroidNativeSettingsShellPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-native-settings-shell"
    override val inject = dependencies(KcodeShellMode.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeShellMode.Key).policy
        applyNativeShell(ctx, effect, ubuntu = false) { policy.mode() }
    }
}

class AndroidNativeSettingsUbuntuShellPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "android-native-settings-ubuntu-shell"
    override val inject = dependencies(KcodeShellMode.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeShellMode.Key).policy
        applyNativeShell(ctx, effect, ubuntu = true) { policy.mode() }
    }
}

/** Explicit custom compositions may continue supplying their own mode policy. */
class AndroidNativeShellPlugin : Plugin<suspend () -> ShellExecutionMode> {
    override val name = "android-native-shell"
    override suspend fun apply(ctx: Context, config: suspend () -> ShellExecutionMode, effect: EffectScope) =
        applyNativeShell(ctx, effect, ubuntu = false, mode = config)
}

class AndroidNativeUbuntuShellPlugin : Plugin<suspend () -> ShellExecutionMode> {
    override val name = "android-native-ubuntu-shell"
    override suspend fun apply(ctx: Context, config: suspend () -> ShellExecutionMode, effect: EffectScope) =
        applyNativeShell(ctx, effect, ubuntu = true, mode = config)
}

private fun workspaceConfiguration() = ConfigValidator<Any?> {
    require(it == Unit || it is String && it.isNotBlank() && Path.of(it).isAbsolute) { "Android shell config must be Unit or an absolute workspace path" }
    it
}

private suspend fun applyNativeShell(
    ctx: Context,
    effect: EffectScope,
    ubuntu: Boolean,
    workspace: String? = null,
    mode: suspend () -> ShellExecutionMode,
) {
    val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs) {
        "Android shells require native host inputs"
    }
    val owner = PluginOperationOwner(if (ubuntu) "Android Ubuntu shell provider" else "Android system shell provider")
    val context = inputs.applicationContext()
    val workspaceRoot = workspace?.let { Files.createDirectories(Path.of(it)).toRealPath() }
    val origin = PluginCodeOrigin.current(ctx)
    val readMode: suspend () -> ShellExecutionMode = { owner.run { mode() } }
    val executor: AgentShellExecutor = if (ubuntu) {
        AndroidUbuntuShellExecutor(context, readMode, origin, workspaceRoot)
    } else {
        AndroidShellExecutors(context, readMode, origin, workspaceRoot)
    }
    effect.collect {
        val failures = mutableListOf<Throwable>()
        runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
        runCatching { (executor as? AutoCloseable)?.close() }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) throw PluginCleanupException("Android shell provider", failures)
    }
    val backend = ShellBackend { request ->
        owner.run {
            executor.execute(request.command, request.workingDirectory).let { ShellResult(it.output, it.exitCode) }
        }
    }
    if (ubuntu) KcodeUbuntuShell(ctx, backend) else KcodeShell(ctx, backend)
}
