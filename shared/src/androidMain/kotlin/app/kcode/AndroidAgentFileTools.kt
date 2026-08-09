package app.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.serialization.typeToken
import android.app.Activity
import android.content.Context
import app.kcode.h5.AndroidH5ContainerLauncher
import app.kcode.settings.ShellExecutionMode
import app.kcode.settings.ToolPermissionMode
import app.kcode.tools.permission.ToolCallApprover
import app.kcode.tools.search.WebSearchTool
import app.kcode.tools.search.WebSearchConfiguration
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

/** Creates an Android agent whose file tools are confined to the app-private /workspace directory. */
fun createAndroidKoogChatService(
    activity: Activity,
    modeProvider: suspend () -> ShellExecutionMode,
    permissionModeProvider: suspend () -> ToolPermissionMode,
    webSearchConfigurationProvider: suspend () -> WebSearchConfiguration,
    toolCallApprover: ToolCallApprover,
): KoogChatService {
    val fileSystem = AndroidAgentWorkspaceFileSystem(activity.applicationContext)
    val shellExecutors = AndroidShellExecutors(activity, fileSystem.workspaceRoot)
    val fileTools = ToolRegistry {
        tool(ReadFileTool(fileSystem))
        tool(ListDirectoryTool(fileSystem))
        tool(WriteFileTool(fileSystem))
        tool(EditFileTool(fileSystem))
        tool(H5PreviewTool(AndroidH5ContainerLauncher(activity.applicationContext)))
        tool(WebSearchTool(webSearchConfigurationProvider))
        tool(AndroidShellTool(
            modeProvider = modeProvider,
            executeCommand = shellExecutors::execute,
        ))
    }
    return KoogChatService(
        additionalTools = fileTools,
        toolPermissionModeProvider = permissionModeProvider,
        toolCallApprover = toolCallApprover,
    )
}

internal class AndroidShellTool(
    private val modeProvider: suspend () -> ShellExecutionMode,
    private val executeCommand: suspend (ShellExecutionMode, String, Int) -> String,
) : SimpleTool<AndroidShellTool.Args>(
    argsType = typeToken<Args>(),
    name = "android_shell",
    description = """
        Executes a shell command on the Android device using the permission mode selected by the user in Settings:
        app UID, adb shell UID 2000 through Shizuku, or root through su.
        App UID and root use the file tools' app-private workspace. adb shell uses /data/local/tmp/kcode-2000 because UID 2000 cannot access app-private files.
        Execution is governed by kcode's global tool permission gate. Never assume a higher mode is available and never retry using another mode.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Shell command to execute with /system/bin/sh -c")
        val command: String,
        @property:LLMDescription("Timeout in seconds, from 1 to 20; defaults to 10")
        val timeoutSeconds: Int = 10,
    )

    override suspend fun execute(args: Args): String {
        val command = args.command.trim()
        require(command.isNotEmpty()) { "Command must not be empty" }
        require(command.length <= MAX_COMMAND_CHARS) { "Command is too long" }
        val mode = modeProvider()
        val timeoutSeconds = args.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        return executeCommand(mode, command, timeoutSeconds)
    }

    private companion object {
        const val MAX_COMMAND_CHARS = 8_192
        const val MAX_TIMEOUT_SECONDS = 20
    }
}

/**
 * Maps the virtual absolute path `/workspace/...` onto app-private storage.
 *
 * The model never receives the physical Android path. Normalization, containment checks and
 * symbolic-link rejection are applied at every provider boundary.
 */
internal class AndroidAgentWorkspaceFileSystem(rootPath: Path) : FileSystemProvider.ReadWrite<Path> {
    constructor(context: Context) : this(context.filesDir.toPath().resolve(WORKSPACE_DIRECTORY))

    private val root: Path = rootPath
        .toAbsolutePath()
        .normalize()
        .also(Files::createDirectories)

    internal val workspaceRoot: Path get() = root

    override fun toAbsolutePathString(path: Path): String {
        val checked = checked(path)
        val relative = root.relativize(checked).joinToString("/") { it.toString() }
        return if (relative.isEmpty()) VIRTUAL_ROOT else "$VIRTUAL_ROOT/$relative"
    }

    override fun fromAbsolutePathString(path: String): Path {
        require(path == VIRTUAL_ROOT || path.startsWith("$VIRTUAL_ROOT/")) {
            "Only $VIRTUAL_ROOT and its descendants are accessible"
        }
        require('\\' !in path && '\u0000' !in path) { "Invalid workspace path" }
        val relative = path.removePrefix(VIRTUAL_ROOT).trimStart('/')
        return checked(if (relative.isEmpty()) root else root.resolve(relative))
    }

    override fun joinPath(base: Path, vararg parts: String): Path {
        var result = checked(base)
        parts.forEach { part ->
            require(part.isNotEmpty() && !part.startsWith('/') && !part.startsWith('\\')) {
                "Path components must be relative"
            }
            result = checked(result.resolve(part))
        }
        return result
    }

    override fun name(path: Path): String = if (checked(path) == root) "workspace" else path.fileName.toString()

    override fun extension(path: Path): String {
        val name = name(path)
        val separator = name.lastIndexOf('.')
        return if (separator <= 0 || separator == name.lastIndex) "" else name.substring(separator + 1)
    }

    override suspend fun metadata(path: Path): FileMetadata? = io {
        val target = checked(path)
        when {
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ->
                FileMetadata(FileMetadata.FileType.File, name(target).startsWith('.'))
            Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ->
                FileMetadata(FileMetadata.FileType.Directory, name(target).startsWith('.'))
            else -> null
        }
    }

    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType = io {
        val target = requireRegularFile(path)
        if (Files.size(target) > MAX_FILE_BYTES) {
            throw IOException("File exceeds the $MAX_FILE_BYTES-byte workspace limit")
        }
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
        require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "Path must be a directory" }
        Files.newDirectoryStream(target).use { entries ->
            entries.map(::checked).sortedBy { it.fileName?.toString().orEmpty() }
        }
    }

    override fun parent(path: Path): Path? {
        val target = checked(path)
        return if (target == root) null else checked(target.parent)
    }

    override fun relativize(root: Path, path: Path): String? {
        val checkedRoot = checked(root)
        val checkedPath = checked(path)
        if (!checkedPath.startsWith(checkedRoot)) return null
        return checkedRoot.relativize(checkedPath).joinToString("/") { it.toString() }
    }

    override suspend fun exists(path: Path): Boolean = io {
        Files.exists(checked(path), LinkOption.NOFOLLOW_LINKS)
    }

    override suspend fun readBytes(path: Path): ByteArray = io {
        val target = requireRegularFile(path)
        val fileSize = Files.size(target)
        require(fileSize <= MAX_FILE_BYTES) { "File exceeds the $MAX_FILE_BYTES-byte workspace limit" }
        Files.readAllBytes(target)
    }

    override suspend fun inputStream(path: Path): Source = io {
        val target = requireRegularFile(path)
        require(Files.size(target) <= MAX_FILE_BYTES) { "File exceeds the $MAX_FILE_BYTES-byte workspace limit" }
        SystemFileSystem.source(kotlinx.io.files.Path(target.toString())).buffered()
    }

    override suspend fun size(path: Path): Long = io { Files.size(requireRegularFile(path)) }

    override suspend fun create(path: Path, type: FileMetadata.FileType): Unit = io {
        val target = writableTarget(path)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Target already exists" }
        enforceEntryLimit(creatingNewEntry = true)
        target.parent?.let(Files::createDirectories)
        when (type) {
            FileMetadata.FileType.File -> Files.createFile(target)
            FileMetadata.FileType.Directory -> Files.createDirectory(target)
        }
    }

    override suspend fun move(source: Path, target: Path): Unit = io {
        val from = checked(source)
        val to = writableTarget(target)
        require(from != root) { "The workspace root cannot be moved" }
        require(Files.exists(from, LinkOption.NOFOLLOW_LINKS)) { "Source does not exist" }
        require(!Files.exists(to, LinkOption.NOFOLLOW_LINKS)) { "Target already exists" }
        to.parent?.let(Files::createDirectories)
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun copy(source: Path, target: Path): Unit = io {
        val from = checked(source)
        val to = writableTarget(target)
        require(Files.exists(from, LinkOption.NOFOLLOW_LINKS)) { "Source does not exist" }
        require(!Files.exists(to, LinkOption.NOFOLLOW_LINKS)) { "Target already exists" }
        val addedBytes = treeSize(from)
        require(workspaceSize() + addedBytes <= MAX_WORKSPACE_BYTES) { "Workspace size limit exceeded" }
        copyTree(from, to)
        require(entryCount() <= MAX_WORKSPACE_ENTRIES) { "Workspace entry limit exceeded" }
    }

    override suspend fun writeBytes(path: Path, data: ByteArray): Unit = io {
        require(data.size <= MAX_FILE_BYTES) { "File exceeds the $MAX_FILE_BYTES-byte workspace limit" }
        val target = writableTarget(path)
        require(!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "Target is a directory" }
        val oldSize = if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) Files.size(target) else 0L
        enforceEntryLimit(creatingNewEntry = !Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        require(workspaceSize() - oldSize + data.size <= MAX_WORKSPACE_BYTES) { "Workspace size limit exceeded" }
        target.parent?.let(Files::createDirectories)
        Files.write(target, data)
    }

    override suspend fun outputStream(path: Path, append: Boolean): Sink = io {
        val target = writableTarget(path)
        target.parent?.let(Files::createDirectories)
        SystemFileSystem.sink(kotlinx.io.files.Path(target.toString()), append = append).buffered()
    }

    override suspend fun delete(path: Path): Unit = io {
        val target = checked(path)
        require(target != root) { "The workspace root cannot be deleted" }
        require(Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Path does not exist" }
        deleteTree(target)
    }

    private fun checked(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path escapes $VIRTUAL_ROOT" }
        rejectSymbolicLinks(normalized)
        return normalized
    }

    private fun writableTarget(path: Path): Path = checked(path).also {
        require(it != root) { "The workspace root is not a file" }
    }

    private fun requireRegularFile(path: Path): Path = checked(path).also {
        require(Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)) { "Path must be a regular file" }
    }

    private fun rejectSymbolicLinks(path: Path) {
        var current = root
        root.relativize(path).forEach { component ->
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw SecurityException("Symbolic links are not allowed in $VIRTUAL_ROOT")
            }
        }
    }

    private fun enforceEntryLimit(creatingNewEntry: Boolean) {
        if (creatingNewEntry) require(entryCount() < MAX_WORKSPACE_ENTRIES) { "Workspace entry limit exceeded" }
    }

    private fun entryCount(): Long = Files.walk(root).use { it.count() - 1L }

    private fun workspaceSize(): Long = treeSize(root)

    private fun treeSize(path: Path): Long = Files.walk(path).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .mapToLong(Files::size)
            .sum()
    }

    private fun copyTree(source: Path, target: Path) {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target)
            Files.newDirectoryStream(source).use { children ->
                children.forEach { child -> copyTree(checked(child), target.resolve(child.fileName)) }
            }
        } else {
            target.parent?.let(Files::createDirectories)
            Files.copy(source, target)
        }
    }

    private fun deleteTree(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { children -> children.forEach { deleteTree(checked(it)) } }
        }
        Files.delete(path)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val WORKSPACE_DIRECTORY = "agent_workspace"
        const val VIRTUAL_ROOT = "/workspace"
        const val TEXT_PROBE_BYTES = 8_000
        const val MAX_FILE_BYTES = 1_048_576L
        const val MAX_WORKSPACE_BYTES = 16_777_216L
        const val MAX_WORKSPACE_ENTRIES = 512L
    }
}
