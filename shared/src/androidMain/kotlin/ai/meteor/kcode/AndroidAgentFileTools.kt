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
import ai.meteor.kcode.skill.createWorkspaceSkillRuntime
import ai.meteor.kcode.skill.skillTools
import ai.meteor.kcode.artifact.createAndroidArtifactRepository
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
    val workspaceRoot = Files.createDirectories(activity.filesDir.toPath().resolve("agent_workspace")).toRealPath()
    val fileSystem = AndroidAgentFileSystem(workspaceRoot)
    val shellExecutor = AndroidShellExecutors(
        activity = activity,
        modeProvider = modeProvider,
    )
    val skillWorkspace = AndroidPrivateAgentWorkspace(workspaceRoot)
    val skillRuntime = createWorkspaceSkillRuntime(skillWorkspace, "android-app-data")
    val artifactRepository = createAndroidArtifactRepository(activity.applicationContext)
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
        skillTools(skillRuntime)
        artifactTools(artifactRepository)
    }
    return KcodeAgentRuntime(
        chatService = KoogChatService(
            additionalTools = fileTools,
            toolPermissionModeProvider = permissionModeProvider,
            toolCallApprover = toolCallApprover,
            skillRuntime = skillRuntime,
        ),
        webContainerController = webContainerController,
        artifactRepository = artifactRepository,
    )
}

internal class AndroidPrivateAgentWorkspace(
    private val root: Path,
) : AgentWorkspace {
    private val normalizedRoot = root.toAbsolutePath().normalize()

    override suspend fun readText(path: String): String = io {
        val target = checked(path, allowRoot = false)
        require(Files.isRegularFile(target)) { "File does not exist: $path" }
        require(Files.size(target) <= MaxFileBytes) { "File exceeds the $MaxFileBytes-byte limit" }
        Files.readAllBytes(target).decodeToString()
    }

    override suspend fun writeText(path: String, content: String) = io {
        val bytes = content.encodeToByteArray()
        require(bytes.size <= MaxFileBytes) { "File exceeds the $MaxFileBytes-byte limit" }
        val target = checked(path, allowRoot = false)
        target.parent?.let(Files::createDirectories)
        Files.write(target, bytes)
        Unit
    }

    override suspend fun list(path: String): List<AgentWorkspaceEntry> = io {
        val directory = checked(path, allowRoot = true)
        require(Files.isDirectory(directory)) { "Directory does not exist: $path" }
        Files.newDirectoryStream(directory).use { children ->
            children.map { child ->
                val safeChild = checkedPhysical(child)
                AgentWorkspaceEntry(
                    path = virtualPath(safeChild),
                    directory = Files.isDirectory(safeChild),
                    size = if (Files.isRegularFile(safeChild)) Files.size(safeChild) else 0L,
                )
            }.sortedBy { it.path }
        }
    }

    override suspend fun canonicalize(path: String): String = io {
        virtualPath(checked(path, allowRoot = false).toRealPath())
    }

    private fun checked(path: String, allowRoot: Boolean): Path {
        require(path == "/workspace" || path.startsWith("/workspace/")) { "Path must be inside /workspace" }
        require(allowRoot || path != "/workspace") { "The workspace root is not a file" }
        val relative = path.removePrefix("/workspace").trimStart('/')
        require('\\' !in relative && '\u0000' !in relative) { "Invalid workspace path" }
        require(relative.split('/').none { it == "." || it == ".." }) { "Path traversal is not allowed" }
        return checkedPhysical(relative.split('/').filter(String::isNotEmpty).fold(normalizedRoot, Path::resolve))
    }

    private fun checkedPhysical(path: Path): Path {
        val candidate = path.toAbsolutePath().normalize()
        require(candidate.startsWith(normalizedRoot)) { "Path escapes /workspace" }
        var existing = candidate
        while (!Files.exists(existing) && existing != normalizedRoot) existing = existing.parent
        require(existing.toRealPath().startsWith(normalizedRoot)) { "Path escapes /workspace through a symbolic link" }
        return candidate
    }

    private fun virtualPath(path: Path): String {
        val relative = normalizedRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        return if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val MaxFileBytes = 1_048_576L
    }
}

/** Keeps permission prompts visible while an agent-controlled Web container is in front. */
fun activeAndroidWebContainerActivity(): Activity? = AndroidWebContainerLauncher.activeContainerActivity()

/** Uses real absolute filesystem paths without an application-level containment boundary. */
internal class AndroidAgentFileSystem(
    workspaceRoot: Path? = null,
) : FileSystemProvider.ReadWrite<Path> {
    private val workspaceRoot = workspaceRoot?.toAbsolutePath()?.normalize()

    override fun toAbsolutePathString(path: Path): String {
        val target = checked(path)
        val root = workspaceRoot
        if (root != null && target.startsWith(root)) {
            val relative = root.relativize(target).toString().replace('\\', '/')
            return if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
        }
        return target.toString()
    }

    override fun fromAbsolutePathString(path: String): Path {
        require('\u0000' !in path) { "Invalid file path" }
        workspaceRoot?.let { root ->
            if (path == "/workspace" || path.startsWith("/workspace/")) {
                val relative = path.removePrefix("/workspace").trimStart('/')
                require('\\' !in relative && relative.split('/').none { it == "." || it == ".." }) {
                    "Invalid /workspace path"
                }
                return checked(relative.split('/').filter(String::isNotEmpty).fold(root, Path::resolve))
            }
        }
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

    private companion object {
        const val TEXT_PROBE_BYTES = 8_000
    }
}
