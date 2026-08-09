package ai.meteor.kcode.artifact

import kotlinx.browser.localStorage

fun createWebArtifactRepository(): ArtifactRepository = FileArtifactRepository(WebArtifactFileStore)

internal object WebArtifactFileStore : ArtifactFileStore {
    override suspend fun readText(path: String): String? = localStorage.getItem(FilePrefix + artifactRelativePath(path))

    override suspend fun exists(path: String): Boolean = localStorage.getItem(FilePrefix + artifactRelativePath(path)) != null

    private const val FilePrefix = "kcode.workspace.file."
}

internal fun artifactRelativePath(path: String): String {
    require(path.startsWith("/workspace/")) { "Artifact path must be inside /workspace" }
    val relative = path.removePrefix("/workspace/")
    require('\\' !in relative && '\u0000' !in relative) { "Invalid artifact path" }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact path" }
    return relative
}
