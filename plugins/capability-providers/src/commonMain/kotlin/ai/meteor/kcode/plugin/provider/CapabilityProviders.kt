package ai.meteor.kcode.plugin.provider

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.api.FileContentKind
import ai.meteor.kcode.plugin.api.FileInfo
import ai.meteor.kcode.plugin.api.FileKind
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.plugin.kcodePlugin
import kotlinx.io.Sink
import kotlinx.io.Source
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.Disposable
import org.cordis.dependencies
import org.cordis.plugin

fun <P> filesystemProviderPlugin(backend: FileSystemProvider.ReadWrite<P>): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.fs.platform", "builtin", "built-in", setOf("fs")),
    PlatformFileSystemProviderPlugin<P>(),
    backend,
)

/** Owns a typed SDK backend while publishing only the neutral filesystem service. */
class PlatformFileSystemProviderPlugin<P> : Plugin<FileSystemProvider.ReadWrite<P>> {
    override val name = "kcode-filesystem-provider"
    override suspend fun apply(ctx: Context, config: FileSystemProvider.ReadWrite<P>, effect: EffectScope) {
        val owner = PluginOperationOwner("filesystem provider")
        effect.collect(Disposable { owner.close() })
        KcodeFileSystem(ctx, PortableFileSystem(config, owner))
    }
}

fun shellProviderPlugin(executor: AgentShellExecutor): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.shell.platform", "builtin", "built-in", setOf("shell")),
    plugin<Unit>(name = "kcode-shell-provider") { ctx, _ ->
        val owner = PluginOperationOwner("shell provider")
        collect(Disposable { owner.close() })
        KcodeShell(ctx, adaptShell(executor, owner))
    },
    Unit,
)

fun ubuntuShellProviderPlugin(executor: AgentShellExecutor): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.shell.ubuntu", "builtin", "built-in", setOf("ubuntuShell")),
    plugin<Unit>(name = "kcode-ubuntu-shell-provider") { ctx, _ ->
        val owner = PluginOperationOwner("Ubuntu shell provider")
        collect(Disposable { owner.close() })
        KcodeUbuntuShell(ctx, adaptShell(executor, owner))
    },
    Unit,
)

/** Builds the platform executor per settings generation; the host supplies only OS bridges. */
fun settingsShellProviderPlugin(
    factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor,
): KcodePluginMount = settingsShellProvider(factory, ubuntu = false)

fun settingsUbuntuShellProviderPlugin(
    factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor,
): KcodePluginMount = settingsShellProvider(factory, ubuntu = true)

private fun settingsShellProvider(
    factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor,
    ubuntu: Boolean,
): KcodePluginMount = kcodePlugin(
    PluginDescriptor(
        if (ubuntu) "provider.shell.ubuntu" else "provider.shell.platform",
        "builtin", "built-in", setOf(if (ubuntu) "ubuntuShell" else "shell"),
    ),
    if (ubuntu) SettingsUbuntuShellProviderPlugin else SettingsShellProviderPlugin,
    factory,
)

object SettingsShellProviderPlugin : Plugin<(suspend () -> ShellExecutionMode) -> AgentShellExecutor> {
    override val name = "kcode-settings-shell-provider"
    override val inject = dependencies(KcodeSettings.Key)
    override suspend fun apply(
        ctx: Context,
        config: (suspend () -> ShellExecutionMode) -> AgentShellExecutor,
        effect: EffectScope,
    ) = applySettingsShell(ctx, config, effect, ubuntu = false)
}

object SettingsUbuntuShellProviderPlugin : Plugin<(suspend () -> ShellExecutionMode) -> AgentShellExecutor> {
    override val name = "kcode-settings-ubuntu-shell-provider"
    override val inject = dependencies(KcodeSettings.Key)
    override suspend fun apply(
        ctx: Context,
        config: (suspend () -> ShellExecutionMode) -> AgentShellExecutor,
        effect: EffectScope,
    ) = applySettingsShell(ctx, config, effect, ubuntu = true)
}

private suspend fun applySettingsShell(
    ctx: Context,
    factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor,
    effect: EffectScope,
    ubuntu: Boolean,
) {
    val store = ctx.require(KcodeSettings.Key).store
    val owner = PluginOperationOwner("settings shell provider")
    effect.collect(Disposable { owner.close() })
    val executor = factory {
        owner.run { ShellExecutionMode.fromCode(store.load().shellExecutionMode) ?: ShellExecutionMode.App }
    }
    if (ubuntu) KcodeUbuntuShell(ctx, adaptShell(executor, owner))
    else KcodeShell(ctx, adaptShell(executor, owner))
}

/** Erases only the platform path representation; all containment and IO checks stay in the backend. */
private fun adaptShell(executor: AgentShellExecutor, owner: PluginOperationOwner) = ShellBackend { request ->
    owner.run {
        executor.execute(request.command, request.workingDirectory).let { ShellResult(it.output, it.exitCode) }
    }
}

private class PortableFileSystem<P>(
    private val delegate: FileSystemProvider.ReadWrite<P>,
    private val owner: PluginOperationOwner,
) : FileSystemBackend {
    private fun path(value: String): P {
        owner.requireOpen()
        return delegate.fromAbsolutePathString(value)
    }
    private fun display(value: P): String = delegate.toAbsolutePathString(value)
    override fun normalize(path: String): String = display(this.path(path))
    override fun joinPath(base: String, vararg parts: String): String = display(delegate.joinPath(path(base), *parts))
    override fun name(path: String): String = delegate.name(this.path(path))
    override fun extension(path: String): String = delegate.extension(this.path(path))
    override fun parent(path: String): String? = delegate.parent(this.path(path))?.let(::display)
    override fun relativize(root: String, path: String): String? = delegate.relativize(this.path(root), this.path(path))
    override suspend fun metadata(path: String): FileInfo? = owner.run {
        delegate.metadata(this.path(path))?.let {
            FileInfo(if (it.type == FileMetadata.FileType.File) FileKind.File else FileKind.Directory, it.hidden)
        }
    }
    override suspend fun list(directory: String): List<String> = owner.run {
        delegate.list(path(directory)).map(::display)
    }
    override suspend fun exists(path: String): Boolean = owner.run {
        delegate.exists(this.path(path))
    }
    override suspend fun contentKind(path: String): FileContentKind = owner.run {
        if (delegate.getFileContentType(this.path(path)) == FileMetadata.FileContentType.Text) FileContentKind.Text else FileContentKind.Binary
    }
    override suspend fun readBytes(path: String): ByteArray = owner.run {
        delegate.readBytes(this.path(path))
    }
    override suspend fun inputStream(path: String): Source = owner.acquire(
        acquire = { delegate.inputStream(this.path(path)) },
        discard = { it.close() },
    )
    override suspend fun size(path: String): Long = owner.run {
        delegate.size(this.path(path))
    }
    override suspend fun create(path: String, kind: FileKind) = owner.run {
        delegate.create(this.path(path), if (kind == FileKind.File) FileMetadata.FileType.File else FileMetadata.FileType.Directory)
    }
    override suspend fun writeBytes(path: String, data: ByteArray) = owner.run {
        delegate.writeBytes(this.path(path), data)
    }
    override suspend fun outputStream(path: String, append: Boolean): Sink = owner.acquire(
        acquire = { delegate.outputStream(this.path(path), append) },
        discard = { it.close() },
    )
    override suspend fun move(source: String, target: String) = owner.run {
        delegate.move(path(source), path(target))
    }
    override suspend fun copy(source: String, target: String) = owner.run {
        delegate.copy(path(source), path(target))
    }
    override suspend fun delete(path: String) = owner.run {
        delegate.delete(this.path(path))
    }
}
