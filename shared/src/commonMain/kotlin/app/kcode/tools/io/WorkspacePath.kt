package app.kcode.tools.io

internal fun normalizeWorkspacePath(path: String, allowRoot: Boolean): String {
    require(path == "/workspace" || path.startsWith("/workspace/")) {
        "Path must be inside /workspace"
    }
    val relative = path.removePrefix("/workspace").removePrefix("/")
    require(allowRoot || relative.isNotEmpty()) { "Path must name a file inside /workspace" }
    require('\\' !in relative && '\u0000' !in relative) { "Invalid workspace path" }
    val segments = relative.split('/').filter { it.isNotEmpty() }
    require(segments.none { it == "." || it == "" }) { "Path escapes /workspace" }
    return segments.joinToString("/")
}
