package ai.meteor.kcode.h5

data class H5PreviewRequest(
    val entryPath: String,
    val title: String,
)

data class H5PreviewResult(
    val entryPath: String,
    val entrySize: Long,
    val presentation: String,
)

/** Common contract implemented by each platform's native H5 runtime. */
fun interface H5ContainerLauncher {
    suspend fun launch(request: H5PreviewRequest): H5PreviewResult
}

object H5VirtualPath {
    const val ROOT = "/workspace"

    fun relativeEntry(virtualPath: String): String {
        require(virtualPath.startsWith("$ROOT/")) { "H5 entry must be inside /workspace" }
        val relative = virtualPath.removePrefix("$ROOT/")
        require(relative.isNotBlank()) { "H5 entry must name an HTML file" }
        val segments = relative.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Invalid H5 entry path" }
        require('\\' !in relative && '\u0000' !in relative) { "Invalid H5 entry path" }
        require(relative.endsWith(".html", ignoreCase = true) || relative.endsWith(".htm", ignoreCase = true)) {
            "H5 entry must be an .html or .htm file"
        }
        return segments.joinToString("/")
    }
}
