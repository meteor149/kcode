package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.BraveModeConfirmationHandler
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.ext.tool.shell.ShellCommandExecutor
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.meteor.kcode.h5.DesktopH5ContainerLauncher
import ai.meteor.kcode.tools.search.WebSearchTool
import ai.meteor.kcode.tools.search.WebSearchConfiguration
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.swing.Swing
import javax.swing.JOptionPane

fun createDesktopKoogChatService(settingsStore: AppSettingsStore): KoogChatService {
    val workspace = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "workspace"),
    ).toRealPath()
    val fileSystem = DesktopAgentWorkspaceFileSystem(workspace)
    return KoogChatService(
        additionalTools = ToolRegistry {
            tool(ReadFileTool(fileSystem))
            tool(ListDirectoryTool(fileSystem))
            tool(WriteFileTool(fileSystem))
            tool(EditFileTool(fileSystem))
            tool(
                ExecuteShellCommandTool(
                    executor = DesktopShellCommandExecutor(workspace),
                    confirmationHandler = BraveModeConfirmationHandler(),
                ),
            )
            tool(H5PreviewTool(DesktopH5ContainerLauncher(workspace)))
            tool(WebSearchTool(configurationProvider = {
                settingsStore.load().let {
                    WebSearchConfiguration(
                        provider = WebSearchProvider.fromCode(it.webSearchProvider),
                        brightDataApiKey = it.webSearchApiKey,
                        exaApiKey = it.exaSearchApiKey,
                    )
                }
            }))
        },
        toolPermissionModeProvider = {
            ToolPermissionMode.fromCode(settingsStore.load().toolPermissionMode)
        },
        toolCallApprover = ToolCallApprover { request -> confirmDesktopToolCall(request) },
    )
}

internal class DesktopShellCommandExecutor(
    workspace: Path,
    private val delegate: ShellCommandExecutor = JvmShellCommandExecutor(),
) : ShellCommandExecutor {
    private val workspaceRoot = workspace.toRealPath()

    override suspend fun execute(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Int,
    ): ShellCommandExecutor.ExecutionResult {
        val request = normalizeShellCommandRequest(command, workingDirectory, timeoutSeconds)
        val directory = resolveWorkingDirectory(request.relativeWorkingDirectory)
        return delegate.execute(
            command = request.command,
            workingDirectory = directory.toString(),
            timeoutSeconds = request.timeoutSeconds,
        )
    }

    private fun resolveWorkingDirectory(relativePath: String): Path {
        val candidate = if (relativePath.isEmpty()) workspaceRoot else workspaceRoot.resolve(relativePath)
        val directory = candidate.toRealPath()
        require(directory.startsWith(workspaceRoot) && Files.isDirectory(directory)) {
            "Working directory does not exist inside /workspace: ${virtualWorkspacePath(relativePath)}"
        }
        return directory
    }
}

private suspend fun confirmDesktopToolCall(request: ToolApprovalRequest): Boolean =
    withContext(Dispatchers.Swing) {
        JOptionPane.showConfirmDialog(
            null,
            "kcode wants to use ${request.name}.\n\nPurpose\n${request.description.ifBlank { request.name }.take(2_048)}\n\nInput\n${request.input.take(8_192)}",
            "Allow tool call?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        ) == JOptionPane.YES_OPTION
    }

/** Maps the same virtual /workspace contract onto ~/.kcode/workspace on desktop. */
internal class DesktopAgentWorkspaceFileSystem(
    private val root: Path,
) : FileSystemProvider.ReadWrite<Path> {
    private val delegate = JVMFileSystemProvider.ReadWrite
    private val normalizedRoot = root.toAbsolutePath().normalize()

    override fun fromAbsolutePathString(path: String): Path {
        require(path == "/workspace" || path.startsWith("/workspace/")) { "Path must be inside /workspace" }
        val relative = path.removePrefix("/workspace").removePrefix("/")
        require('\\' !in relative && '\u0000' !in relative) { "Invalid workspace path" }
        return checked(relative.split('/').filter { it.isNotEmpty() }.fold(normalizedRoot, Path::resolve))
    }

    override fun toAbsolutePathString(path: Path): String {
        val relative = normalizedRoot.relativize(checked(path)).toString().replace('\\', '/')
        return if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
    }

    override fun joinPath(base: Path, vararg parts: String): Path = checked(
        parts.fold(checked(base)) { current, part ->
            require(!Path.of(part).isAbsolute) { "Path component must be relative" }
            current.resolve(part)
        },
    )

    override fun name(path: Path): String = if (checked(path) == normalizedRoot) "workspace" else path.fileName.toString()
    override fun extension(path: Path): String = delegate.extension(checked(path))
    override fun parent(path: Path): Path? = checked(path).takeIf { it != normalizedRoot }?.parent?.let(::checked)
    override fun relativize(root: Path, path: Path): String? = delegate.relativize(checked(root), checked(path))
    override suspend fun metadata(path: Path): FileMetadata? = delegate.metadata(checked(path))
    override suspend fun list(directory: Path): List<Path> = delegate.list(checked(directory)).map(::checked)
    override suspend fun exists(path: Path): Boolean = delegate.exists(checked(path))
    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType = delegate.getFileContentType(checked(path))
    override suspend fun readBytes(path: Path): ByteArray = delegate.readBytes(checked(path))
    override suspend fun inputStream(path: Path): Source = delegate.inputStream(checked(path))
    override suspend fun size(path: Path): Long = delegate.size(checked(path))
    override suspend fun create(path: Path, type: FileMetadata.FileType) = delegate.create(checked(path), type)
    override suspend fun writeBytes(path: Path, data: ByteArray) = delegate.writeBytes(checked(path), data)
    override suspend fun outputStream(path: Path, append: Boolean): Sink = delegate.outputStream(checked(path), append)
    override suspend fun move(source: Path, target: Path) = delegate.move(checked(source), checked(target))
    override suspend fun copy(source: Path, target: Path) = delegate.copy(checked(source), checked(target))
    override suspend fun delete(path: Path) {
        require(checked(path) != normalizedRoot) { "The workspace root cannot be deleted" }
        delegate.delete(checked(path))
    }

    private fun checked(path: Path): Path {
        val candidate = path.toAbsolutePath().normalize()
        require(candidate.startsWith(normalizedRoot)) { "Path escapes /workspace" }
        var existing = candidate
        while (!Files.exists(existing) && existing != normalizedRoot) existing = existing.parent
        require(existing.toRealPath().startsWith(normalizedRoot)) { "Path escapes /workspace through a symbolic link" }
        return candidate
    }
}
