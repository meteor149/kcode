package ai.meteor.kcode

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class DesktopAgentWorkspace(
    private val root: Path,
) : AgentWorkspace {
    private val normalizedRoot = root.toAbsolutePath().normalize()

    override suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val target = checked(path, allowRoot = false)
        require(Files.isRegularFile(target)) { "File does not exist: $path" }
        Files.readString(target)
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val bytes = content.encodeToByteArray()
        require(bytes.size <= MaxFileBytes) { "File exceeds the $MaxFileBytes-byte limit" }
        val target = checked(path, allowRoot = false)
        target.parent?.let(Files::createDirectories)
        Files.write(target, bytes)
        Unit
    }

    override suspend fun list(path: String): List<AgentWorkspaceEntry> = withContext(Dispatchers.IO) {
        val directory = checked(path, allowRoot = true)
        require(Files.isDirectory(directory)) { "Directory does not exist: $path" }
        Files.newDirectoryStream(directory).use { children ->
            children.map { child ->
                val checkedChild = checkedPhysical(child)
                AgentWorkspaceEntry(
                    path = virtualPath(checkedChild),
                    directory = Files.isDirectory(checkedChild),
                    size = if (Files.isRegularFile(checkedChild)) Files.size(checkedChild) else 0L,
                )
            }.sortedBy { it.path }
        }
    }

    override suspend fun canonicalize(path: String): String = withContext(Dispatchers.IO) {
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

    private companion object {
        const val MaxFileBytes = 1_048_576L
    }
}

class DesktopShellCommandExecutor(
    workspace: Path,
    private val delegate: AgentShellExecutor = JvmAgentShellExecutor(),
) : AgentShellExecutor {
    private val workspaceRoot = workspace.toRealPath()

    override suspend fun execute(
        command: String,
        workingDirectory: String?,
    ): AgentShellExecutor.ExecutionResult {
        val request = normalizeShellCommandRequest(command, workingDirectory)
        val directory = resolveWorkingDirectory(request.relativeWorkingDirectory)
        return delegate.execute(
            command = request.command,
            workingDirectory = directory.toString(),
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

private class JvmAgentShellExecutor : AgentShellExecutor {
    override suspend fun execute(command: String, workingDirectory: String?): AgentShellExecutor.ExecutionResult =
        coroutineScope {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(commandLine(command))
                    .directory(workingDirectory?.let { java.io.File(it) })
                    .redirectErrorStream(true)
                    .start()
            }
            val output = async(Dispatchers.IO) {
                process.inputStream.use { it.readBytes().decodeToString() }
            }
            try {
                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                AgentShellExecutor.ExecutionResult(output.await(), exitCode)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

    private fun commandLine(command: String): List<String> =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("cmd.exe", "/d", "/s", "/c", command)
        } else {
            listOf("/bin/sh", "-c", command)
        }
}

/** Maps the same virtual /workspace contract onto ~/.kcode/workspace on desktop. */
class DesktopAgentWorkspaceFileSystem(
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
