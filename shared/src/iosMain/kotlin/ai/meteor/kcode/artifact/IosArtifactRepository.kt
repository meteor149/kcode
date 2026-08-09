@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.meteor.kcode.artifact

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

fun createIosArtifactRepository(workspaceRoot: String): MutableArtifactRepository =
    FileArtifactRepository(IosArtifactFileStore(workspaceRoot))

internal class IosArtifactFileStore(
    workspaceRoot: String,
) : MutableArtifactFileStore {
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
        SystemFileSystem.exists(resolve(path))
    }

    override suspend fun readBytes(path: String): ByteArray? = withContext(Dispatchers.Default) {
        val target = resolve(path)
        if (SystemFileSystem.metadataOrNull(target)?.isRegularFile != true) null
        else SystemFileSystem.source(target).buffered().use { it.readByteArray() }
    }

    override suspend fun writeBytesAtomically(path: String, contents: ByteArray) = withContext(Dispatchers.Default) {
        val target = resolve(path)
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        val temporary = Path(requireNotNull(target.parent), ".kcode-artifact-${Random.nextLong().toString(16)}.tmp")
        try {
            SystemFileSystem.sink(temporary).buffered().use { it.write(contents) }
            SystemFileSystem.atomicMove(temporary, target)
        } finally {
            if (SystemFileSystem.exists(temporary)) SystemFileSystem.delete(temporary)
        }
    }

    override suspend fun list(path: String): List<ArtifactFileEntry> = withContext(Dispatchers.Default) {
        val directory = resolve(path)
        require(SystemFileSystem.metadataOrNull(directory)?.isDirectory == true) { "Directory does not exist: $path" }
        SystemFileSystem.list(directory).map { child ->
            val metadata = requireNotNull(SystemFileSystem.metadataOrNull(child))
            ArtifactFileEntry(
                path = virtualPath(child),
                directory = metadata.isDirectory,
                size = if (metadata.isRegularFile) metadata.size else 0L,
            )
        }.sortedBy { it.path }
    }

    override suspend fun deleteTree(path: String) = withContext(Dispatchers.Default) {
        val target = resolve(path)
        if (SystemFileSystem.exists(target)) deleteRecursively(target)
    }

    override suspend fun moveTree(source: String, target: String) = withContext(Dispatchers.Default) {
        val from = resolve(source)
        val to = resolve(target)
        require(SystemFileSystem.exists(from) && !SystemFileSystem.exists(to)) { "Invalid artifact move" }
        to.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.atomicMove(from, to)
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

    private fun virtualPath(path: Path): String {
        val relative = path.toString().removePrefix(resolvedRoot).trimStart('/')
        return "/workspace/$relative"
    }

    private fun deleteRecursively(path: Path) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
        SystemFileSystem.delete(path)
    }
}

internal fun artifactRelativePath(path: String): String {
    require(path.startsWith("/workspace/")) { "Artifact path must be inside /workspace" }
    val relative = path.removePrefix("/workspace/")
    require('\\' !in relative && '\u0000' !in relative) { "Invalid artifact path" }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact path" }
    return relative
}
