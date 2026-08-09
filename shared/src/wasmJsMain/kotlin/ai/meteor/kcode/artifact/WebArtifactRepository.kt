package ai.meteor.kcode.artifact

import kotlinx.browser.localStorage

fun createWebArtifactRepository(): MutableArtifactRepository = FileArtifactRepository(WebArtifactFileStore)

internal object WebArtifactFileStore : MutableArtifactFileStore {
    override suspend fun readText(path: String): String? = localStorage.getItem(FilePrefix + artifactRelativePath(path))

    override suspend fun exists(path: String): Boolean {
        val relative = artifactRelativePath(path)
        return localStorage.getItem(FilePrefix + relative) != null || workspaceFiles().any { it.first.startsWith("$relative/") }
    }

    override suspend fun readBytes(path: String): ByteArray? = readText(path)?.encodeToByteArray()

    override suspend fun writeBytesAtomically(path: String, contents: ByteArray) {
        val key = FilePrefix + artifactRelativePath(path)
        val value = contents.decodeToString()
        val previousBytes = localStorage.getItem(key).orEmpty().encodeToByteArray().size
        val nextBytes = workspaceFiles().sumOf { it.second.encodeToByteArray().size } - previousBytes + contents.size
        require(nextBytes <= MaxWorkspaceBytes) { "Browser workspace size limit exceeded" }
        localStorage.setItem(key, value)
    }

    override suspend fun list(path: String): List<ArtifactFileEntry> {
        val directory = artifactRelativePath(path)
        val prefix = "$directory/"
        val entries = linkedMapOf<String, ArtifactFileEntry>()
        workspaceFiles().forEach { (relative, contents) ->
            if (!relative.startsWith(prefix)) return@forEach
            val remainder = relative.removePrefix(prefix)
            if (remainder.isEmpty()) return@forEach
            val name = remainder.substringBefore('/')
            val child = "$prefix$name"
            val childDirectory = '/' in remainder
            entries[child] = ArtifactFileEntry(
                path = "/workspace/$child",
                directory = childDirectory,
                size = if (childDirectory) 0L else contents.encodeToByteArray().size.toLong(),
            )
        }
        require(entries.isNotEmpty() || workspaceFiles().any { it.first.startsWith(prefix) }) {
            "Directory does not exist: $path"
        }
        return entries.values.sortedBy { it.path }
    }

    override suspend fun deleteTree(path: String) {
        val relative = artifactRelativePath(path)
        workspaceFiles().map { it.first }
            .filter { it == relative || it.startsWith("$relative/") }
            .forEach { localStorage.removeItem(FilePrefix + it) }
    }

    override suspend fun moveTree(source: String, target: String) {
        val sourceRelative = artifactRelativePath(source)
        val targetRelative = artifactRelativePath(target)
        val moving = workspaceFiles().filter { it.first.startsWith("$sourceRelative/") }
        require(moving.isNotEmpty() && workspaceFiles().none { it.first.startsWith("$targetRelative/") }) {
            "Invalid artifact move"
        }
        moving.forEach { (path, contents) ->
            localStorage.setItem(FilePrefix + targetRelative + path.removePrefix(sourceRelative), contents)
        }
        moving.forEach { localStorage.removeItem(FilePrefix + it.first) }
    }

    private fun workspaceFiles(): List<Pair<String, String>> = buildList {
        for (index in 0 until localStorage.length) {
            val key = localStorage.key(index) ?: continue
            if (key.startsWith(FilePrefix)) add(key.removePrefix(FilePrefix) to localStorage.getItem(key).orEmpty())
        }
    }

    private const val FilePrefix = "kcode.workspace.file."
    private const val MaxWorkspaceBytes = 4_194_304
}

internal fun artifactRelativePath(path: String): String {
    require(path.startsWith("/workspace/")) { "Artifact path must be inside /workspace" }
    val relative = path.removePrefix("/workspace/")
    require('\\' !in relative && '\u0000' !in relative) { "Invalid artifact path" }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact path" }
    return relative
}
