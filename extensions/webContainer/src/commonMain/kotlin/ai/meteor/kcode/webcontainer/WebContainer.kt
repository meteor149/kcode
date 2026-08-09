package ai.meteor.kcode.webcontainer

data class WebPreviewRequest(
    val entryPath: String,
    val title: String,
) {
    val source: WebPreviewSource get() = WebPreviewSource.parse(entryPath)
}

sealed interface WebPreviewSource {
    val location: String

    data class WorkspaceFile(override val location: String) : WebPreviewSource

    data class RemoteWebsite(override val location: String) : WebPreviewSource

    companion object {
        fun parse(location: String): WebPreviewSource {
            val value = location.trim()
            if (value.startsWith("${WebVirtualPath.ROOT}/")) {
                WebVirtualPath.relativeEntry(value)
                return WorkspaceFile(value)
            }
            require(value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
                "Web source must be an HTML file inside /workspace or an http(s) URL"
            }
            require(value.none { it.isWhitespace() || it.code < 0x20 }) { "Invalid remote website URL" }
            val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            val hostAndPort = authority.substringAfterLast('@')
            val host = if (hostAndPort.startsWith('[')) {
                hostAndPort.substringAfter('[').substringBefore(']')
            } else {
                hostAndPort.substringBefore(':')
            }
            require(host.isNotBlank()) { "Remote website URL must include a host" }
            return RemoteWebsite(value)
        }
    }
}

data class WebPreviewResult(
    val containerId: String,
    val entryPath: String,
    val entrySize: Long,
    val presentation: String,
)

data class WebContainerInfo(
    val id: String,
    val entryPath: String,
    val title: String,
    val presentation: String,
    val state: WebContainerState,
)

enum class WebContainerState(val code: String) {
    Foreground("foreground"),
    Background("background"),
    ;

    companion object {
        fun fromCode(code: String): WebContainerState = entries.firstOrNull { it.code == code.lowercase() }
            ?: error("Invalid Web container state: $code. Expected foreground or background")
    }
}

data class WebContainerScreenshot(
    val containerId: String,
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
)

data class WebInteractiveElement(
    val handle: String,
    val tag: String,
    val role: String?,
    val name: String,
    val selector: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val disabled: Boolean,
)

data class WebPageInspection(
    val containerId: String,
    val url: String,
    val title: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val elements: List<WebInteractiveElement>,
)

enum class WebInteractionAction(val code: String) {
    Click("click"),
    Input("input"),
    Scroll("scroll"),
    Key("key"),
    Reload("reload"),
    Back("back"),
    ;

    companion object {
        fun fromCode(code: String): WebInteractionAction = entries.firstOrNull { it.code == code.lowercase() }
            ?: error("Invalid Web interaction action: $code")
    }
}

data class WebInteractionRequest(
    val containerId: String,
    val action: WebInteractionAction,
    val handle: String? = null,
    val selector: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val key: String? = null,
)

data class WebInteractionResult(
    val containerId: String,
    val action: WebInteractionAction,
    val target: String,
)

data class WebConsoleEntry(
    val sequence: Long,
    val level: String,
    val message: String,
    val source: String? = null,
    val line: Int? = null,
)

data class WebConsoleSnapshot(
    val containerId: String,
    val entries: List<WebConsoleEntry>,
    val nextCursor: Long,
)

/** Common lifecycle contract implemented by each platform's Web runtime. */
interface WebContainerController {
    suspend fun launch(request: WebPreviewRequest): WebPreviewResult
    suspend fun list(): List<WebContainerInfo>
    suspend fun screenshot(containerId: String): WebContainerScreenshot
    suspend fun inspect(containerId: String): WebPageInspection
    suspend fun interact(request: WebInteractionRequest): WebInteractionResult
    suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot
    suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo
    suspend fun close(containerId: String)
}

object WebVirtualPath {
    const val ROOT = "/workspace"

    fun relativeEntry(virtualPath: String): String {
        require(virtualPath.startsWith("$ROOT/")) { "Web entry must be inside /workspace" }
        val relative = virtualPath.removePrefix("$ROOT/")
        require(relative.isNotBlank()) { "Web entry must name an HTML file" }
        val segments = relative.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Invalid Web entry path" }
        require('\\' !in relative && '\u0000' !in relative) { "Invalid Web entry path" }
        require(relative.endsWith(".html", ignoreCase = true) || relative.endsWith(".htm", ignoreCase = true)) {
            "Web entry must be an .html or .htm file"
        }
        return segments.joinToString("/")
    }
}
