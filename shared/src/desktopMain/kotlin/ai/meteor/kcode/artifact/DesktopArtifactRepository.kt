package ai.meteor.kcode.artifact

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun createDesktopArtifactRepository(): MutableArtifactRepository {
    val workspace = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "workspace"),
    ).toRealPath()
    return FileArtifactRepository(DesktopArtifactFileStore(workspace))
}

internal class DesktopArtifactFileStore(
    private val workspaceRoot: Path,
) : MutableArtifactFileStore {
    private val root = workspaceRoot.toAbsolutePath().normalize()

    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        val target = resolve(path)
        if (!Files.isRegularFile(target)) null else Files.readString(target)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(resolve(path))
    }

    override suspend fun readBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val target = resolve(path)
        if (!Files.isRegularFile(target)) null else Files.readAllBytes(target)
    }

    override suspend fun writeBytesAtomically(path: String, contents: ByteArray) = withContext(Dispatchers.IO) {
        val target = resolve(path)
        target.parent?.let(Files::createDirectories)
        val temporary = Files.createTempFile(target.parent, ".kcode-artifact-", ".tmp")
        try {
            Files.write(temporary, contents)
            runCatching {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun list(path: String): List<ArtifactFileEntry> = withContext(Dispatchers.IO) {
        val directory = resolve(path)
        require(Files.isDirectory(directory)) { "Directory does not exist: $path" }
        Files.newDirectoryStream(directory).use { children ->
            children.map { child ->
                val safeChild = resolve(virtualPath(child))
                ArtifactFileEntry(
                    path = virtualPath(safeChild),
                    directory = Files.isDirectory(safeChild),
                    size = if (Files.isRegularFile(safeChild)) Files.size(safeChild) else 0L,
                )
            }.sortedBy { it.path }
        }
    }

    override suspend fun deleteTree(path: String) = withContext(Dispatchers.IO) {
        val target = resolve(path)
        if (Files.exists(target)) deleteRecursively(target)
    }

    override suspend fun moveTree(source: String, target: String) = withContext(Dispatchers.IO) {
        val from = resolve(source)
        val to = resolve(target)
        require(Files.exists(from) && !Files.exists(to)) { "Invalid artifact move" }
        to.parent?.let(Files::createDirectories)
        Files.move(from, to)
        Unit
    }

    private fun resolve(path: String): Path {
        val relative = artifactRelativePath(path)
        val candidate = relative.split('/').fold(root, Path::resolve).normalize()
        require(candidate.startsWith(root)) { "Artifact path escapes /workspace" }
        var existing = candidate
        while (!Files.exists(existing) && existing != root) existing = existing.parent
        require(existing.toRealPath().startsWith(root)) { "Artifact path escapes /workspace through a symbolic link" }
        return candidate
    }

    private fun virtualPath(path: Path): String {
        val relative = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        return "/workspace/$relative"
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteRecursively) }
        }
        Files.deleteIfExists(path)
    }
}

internal fun artifactRelativePath(path: String): String {
    require(path.startsWith("/workspace/")) { "Artifact path must be inside /workspace" }
    val relative = path.removePrefix("/workspace/")
    require('\\' !in relative && '\u0000' !in relative) { "Invalid artifact path" }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact path" }
    return relative
}
