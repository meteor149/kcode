@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.meteor.kcode.artifact

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun createIosArtifactRepository(workspaceRoot: String): ArtifactRepository =
    FileArtifactRepository(IosArtifactFileStore(workspaceRoot))

internal class IosArtifactFileStore(
    workspaceRoot: String,
) : ArtifactFileStore {
    private val root = Path(workspaceRoot)
    private val resolvedRoot: String

    init {
        SystemFileSystem.createDirectories(root)
        resolvedRoot = SystemFileSystem.resolve(root).toString().trimEnd('/')
    }

    override suspend fun readText(path: String): String? = withContext(Dispatchers.Default) {
        val target = resolve(path)
        if (SystemFileSystem.metadataOrNull(target)?.isRegularFile != true) null
        else SystemFileSystem.source(target).buffered().use { it.readByteArray().decodeToString() }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.Default) {
        SystemFileSystem.metadataOrNull(resolve(path))?.isRegularFile == true
    }

    private fun resolve(path: String): Path {
        val relative = artifactRelativePath(path)
        val target = Path(root, *relative.split('/').toTypedArray())
        var existing: Path? = target
        while (existing != null && !SystemFileSystem.exists(existing)) existing = existing.parent
        val resolved = SystemFileSystem.resolve(requireNotNull(existing)).toString().trimEnd('/')
        require(resolved == resolvedRoot || resolved.startsWith("$resolvedRoot/")) {
            "Artifact path escapes /workspace through a symbolic link"
        }
        return target
    }
}

internal fun artifactRelativePath(path: String): String {
    require(path.startsWith("/workspace/")) { "Artifact path must be inside /workspace" }
    val relative = path.removePrefix("/workspace/")
    require('\\' !in relative && '\u0000' !in relative) { "Invalid artifact path" }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact path" }
    return relative
}
