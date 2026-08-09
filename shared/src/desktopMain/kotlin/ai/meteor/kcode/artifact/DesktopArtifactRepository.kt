package ai.meteor.kcode.artifact

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun createDesktopArtifactRepository(): ArtifactRepository {
    val workspace = Files.createDirectories(
        Path.of(System.getProperty("user.home"), ".kcode", "workspace"),
    ).toRealPath()
    return FileArtifactRepository(DesktopArtifactFileStore(workspace))
}

internal class DesktopArtifactFileStore(
    private val workspaceRoot: Path,
) : ArtifactFileStore {
    private val root = workspaceRoot.toAbsolutePath().normalize()

    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        val target = resolve(path)
        if (!Files.isRegularFile(target)) null else Files.readString(target)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        Files.isRegularFile(resolve(path))
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
}

internal fun artifactRelativePath(path: String): String {
    require(path.startsWith("/workspace/")) { "Artifact path must be inside /workspace" }
    val relative = path.removePrefix("/workspace/")
    require('\\' !in relative && '\u0000' !in relative) { "Invalid artifact path" }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact path" }
    return relative
}
