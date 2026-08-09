package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.BraveModeConfirmationHandler
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import android.app.Activity
import ai.meteor.kcode.webcontainer.AndroidWebContainerLauncher
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.tools.search.WebSearchTool
import ai.meteor.kcode.tools.search.WebSearchConfiguration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

/** Creates an Android agent whose file tools use real absolute paths allowed by the OS. */
fun createAndroidKoogChatService(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode,
    permissionModeProvider: suspend () -> ToolPermissionMode,
    webSearchConfigurationProvider: suspend () -> WebSearchConfiguration,
    toolCallApprover: ToolCallApprover,
): KoogChatService = createAndroidKoogChatRuntime(
    activity,
    modeProvider,
    permissionModeProvider,
    webSearchConfigurationProvider,
    toolCallApprover,
).chatService

fun createAndroidKoogChatRuntime(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode,
    permissionModeProvider: suspend () -> ToolPermissionMode,
    webSearchConfigurationProvider: suspend () -> WebSearchConfiguration,
    toolCallApprover: ToolCallApprover,
): KcodeAgentRuntime {
    val fileSystem = AndroidAgentFileSystem
    val shellExecutor = AndroidShellExecutors(
        activity = activity,
        modeProvider = modeProvider,
    )
    val webContainerController = AndroidWebContainerLauncher(activity.applicationContext)
    val fileTools = ToolRegistry {
        tool(ReadFileTool(fileSystem))
        tool(ListDirectoryTool(fileSystem))
        tool(WriteFileTool(fileSystem))
        tool(EditFileTool(fileSystem))
        tool(ReadMediaFileTool(fileSystem))
        webContainerTools(webContainerController)
        tool(WebSearchTool(webSearchConfigurationProvider))
        tool(ExecuteShellCommandTool(shellExecutor, BraveModeConfirmationHandler()))
    }
    return KcodeAgentRuntime(
        chatService = KoogChatService(
            additionalTools = fileTools,
            toolPermissionModeProvider = permissionModeProvider,
            toolCallApprover = toolCallApprover,
        ),
        webContainerController = webContainerController,
    )
}

/** Keeps permission prompts visible while an agent-controlled Web container is in front. */
fun activeAndroidWebContainerActivity(): Activity? = AndroidWebContainerLauncher.activeContainerActivity()

/** Uses real absolute filesystem paths without an application-level containment boundary. */
internal object AndroidAgentFileSystem : FileSystemProvider.ReadWrite<Path> {
    override fun toAbsolutePathString(path: Path): String = checked(path).toString()

    override fun fromAbsolutePathString(path: String): Path {
        require('\u0000' !in path) { "Invalid file path" }
        return checked(Path.of(path))
    }

    override fun joinPath(base: Path, vararg parts: String): Path {
        return parts.fold(checked(base)) { current, part ->
            val component = Path.of(part)
            require(!component.isAbsolute) { "Path component must be relative: $part" }
            current.resolve(component)
        }.normalize()
    }

    override fun name(path: Path): String = checked(path).fileName?.toString().orEmpty()

    override fun extension(path: Path): String {
        val name = name(path)
        val separator = name.lastIndexOf('.')
        return if (separator <= 0 || separator == name.lastIndex) "" else name.substring(separator + 1)
    }

    override suspend fun metadata(path: Path): FileMetadata? = io {
        val target = checked(path)
        when {
            Files.isRegularFile(target) -> FileMetadata(FileMetadata.FileType.File, name(target).startsWith('.'))
            Files.isDirectory(target) -> FileMetadata(FileMetadata.FileType.Directory, name(target).startsWith('.'))
            else -> null
        }
    }

    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType = io {
        val target = requireRegularFile(path)
        val head = Files.newInputStream(target).use { input ->
            val buffer = ByteArray(TEXT_PROBE_BYTES)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
        if (head.any { it == 0.toByte() }) {
            FileMetadata.FileContentType.Binary
        } else {
            val text = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head))
            }.isSuccess
            if (text) FileMetadata.FileContentType.Text else FileMetadata.FileContentType.Binary
        }
    }

    override suspend fun list(directory: Path): List<Path> = io {
        val target = checked(directory)
        require(Files.isDirectory(target)) { "Path must be a directory" }
        Files.newDirectoryStream(target).use { entries ->
            entries.map { it.normalize() }.sortedBy { it.fileName?.toString().orEmpty() }
        }
    }

    override fun parent(path: Path): Path? = checked(path).parent

    override fun relativize(root: Path, path: Path): String? = runCatching {
        checked(root).relativize(checked(path)).toString()
    }.getOrNull()

    override suspend fun exists(path: Path): Boolean = io { Files.exists(checked(path)) }

    override suspend fun readBytes(path: Path): ByteArray = io { Files.readAllBytes(requireRegularFile(path)) }

    override suspend fun inputStream(path: Path): Source = io {
        val target = requireRegularFile(path)
        SystemFileSystem.source(kotlinx.io.files.Path(target.toString())).buffered()
    }

    override suspend fun size(path: Path): Long = io { Files.size(requireRegularFile(path)) }

    override suspend fun create(path: Path, type: FileMetadata.FileType): Unit = io {
        val target = checked(path)
        require(!Files.exists(target)) { "Target already exists" }
        target.parent?.let(Files::createDirectories)
        when (type) {
            FileMetadata.FileType.File -> Files.createFile(target)
            FileMetadata.FileType.Directory -> Files.createDirectory(target)
        }
    }

    override suspend fun move(source: Path, target: Path): Unit = io {
        val from = checked(source)
        val to = checked(target)
        require(Files.exists(from)) { "Source does not exist" }
        require(!Files.exists(to)) { "Target already exists" }
        to.parent?.let(Files::createDirectories)
        Files.move(from, to)
    }

    override suspend fun copy(source: Path, target: Path): Unit = io {
        val from = checked(source)
        val to = checked(target)
        require(Files.exists(from)) { "Source does not exist" }
        require(!Files.exists(to)) { "Target already exists" }
        copyTree(from, to)
    }

    override suspend fun writeBytes(path: Path, data: ByteArray): Unit = io {
        val target = checked(path)
        require(!Files.isDirectory(target)) { "Target is a directory" }
        target.parent?.let(Files::createDirectories)
        Files.write(target, data)
    }

    override suspend fun outputStream(path: Path, append: Boolean): Sink = io {
        val target = checked(path)
        require(!Files.isDirectory(target)) { "Target is a directory" }
        target.parent?.let(Files::createDirectories)
        SystemFileSystem.sink(kotlinx.io.files.Path(target.toString()), append = append).buffered()
    }

    override suspend fun delete(path: Path): Unit = io {
        val target = checked(path)
        require(Files.exists(target)) { "Path does not exist" }
        deleteTree(target)
    }

    private fun checked(path: Path): Path {
        val normalized = path.normalize()
        require(normalized.isAbsolute) { "Path must be absolute: $path" }
        return normalized
    }

    private fun requireRegularFile(path: Path): Path = checked(path).also {
        require(Files.isRegularFile(it)) { "Path must be a regular file" }
    }

    private fun copyTree(source: Path, target: Path) {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target)
            Files.newDirectoryStream(source).use { children ->
                children.forEach { child -> copyTree(child, target.resolve(child.fileName)) }
            }
        } else {
            target.parent?.let(Files::createDirectories)
            Files.copy(source, target)
        }
    }

    private fun deleteTree(path: Path) {
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteTree) }
        }
        Files.delete(path)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private const val TEXT_PROBE_BYTES = 8_000
}
