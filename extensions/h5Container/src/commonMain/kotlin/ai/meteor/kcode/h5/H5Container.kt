package ai.meteor.kcode.h5

data class H5PreviewRequest(
    val entryPath: String,
    val title: String,
)

data class H5PreviewResult(
    val containerId: String,
    val entryPath: String,
    val entrySize: Long,
    val presentation: String,
)

data class H5ContainerInfo(
    val id: String,
    val entryPath: String,
    val title: String,
    val presentation: String,
    val state: H5ContainerState,
)

enum class H5ContainerState(val code: String) {
    Foreground("foreground"),
    Background("background"),
    ;

    companion object {
        fun fromCode(code: String): H5ContainerState = entries.firstOrNull { it.code == code.lowercase() }
            ?: error("Invalid H5 container state: $code. Expected foreground or background")
    }
}

data class H5ContainerScreenshot(
    val containerId: String,
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
)

data class H5InteractiveElement(
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

data class H5PageInspection(
    val containerId: String,
    val url: String,
    val title: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val elements: List<H5InteractiveElement>,
)

enum class H5InteractionAction(val code: String) {
    Click("click"),
    Input("input"),
    Scroll("scroll"),
    Key("key"),
    Reload("reload"),
    Back("back"),
    ;

    companion object {
        fun fromCode(code: String): H5InteractionAction = entries.firstOrNull { it.code == code.lowercase() }
            ?: error("Invalid H5 interaction action: $code")
    }
}

data class H5InteractionRequest(
    val containerId: String,
    val action: H5InteractionAction,
    val handle: String? = null,
    val selector: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val key: String? = null,
)

data class H5InteractionResult(
    val containerId: String,
    val action: H5InteractionAction,
    val target: String,
)

data class H5ConsoleEntry(
    val sequence: Long,
    val level: String,
    val message: String,
    val source: String? = null,
    val line: Int? = null,
)

data class H5ConsoleSnapshot(
    val containerId: String,
    val entries: List<H5ConsoleEntry>,
    val nextCursor: Long,
)

/** Common lifecycle contract implemented by each platform's H5 runtime. */
interface H5ContainerController {
    suspend fun launch(request: H5PreviewRequest): H5PreviewResult
    suspend fun list(): List<H5ContainerInfo>
    suspend fun screenshot(containerId: String): H5ContainerScreenshot
    suspend fun inspect(containerId: String): H5PageInspection
    suspend fun interact(request: H5InteractionRequest): H5InteractionResult
    suspend fun console(containerId: String, cursor: Long, limit: Int): H5ConsoleSnapshot
    suspend fun setState(containerId: String, state: H5ContainerState): H5ContainerInfo
    suspend fun close(containerId: String)
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
