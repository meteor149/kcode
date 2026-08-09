package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import kotlinx.serialization.Serializable

class WebPreviewTool(
    private val controller: WebContainerController,
) : SimpleTool<WebPreviewTool.Args>(
    argsType = typeToken<Args>(),
    name = "preview_web_app",
    description = """
        Opens a browser-ready Web application from /workspace or a remote HTTP(S) website in the Web container.
        For a local app, write its assets first and pass entryPath. For a remote website, pass url instead.
        Local relative resources, JavaScript, ES modules, and browser APIs are supported, and local previews cannot access files outside /workspace.
        Remote websites use their standard browser APIs and never receive the local preview's native fallback bridge.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute HTML entry path inside /workspace, for example /workspace/my-app/index.html")
        val entryPath: String? = null,
        @property:LLMDescription("Short title shown by the preview container")
        val title: String = "Web Container",
        @property:LLMDescription("Remote website URL beginning with http:// or https://; mutually exclusive with entryPath")
        val url: String? = null,
    )

    override suspend fun execute(args: Args): String {
        val entryPath = args.entryPath?.trim()?.takeIf { it.isNotEmpty() }
        val url = args.url?.trim()?.takeIf { it.isNotEmpty() }
        require((entryPath == null) != (url == null)) { "Provide exactly one of entryPath or url" }
        val result = controller.launch(
            WebPreviewRequest(
                entryPath = entryPath ?: requireNotNull(url),
                title = args.title.trim().take(MAX_TITLE_LENGTH).ifBlank { "Web Container" },
            ),
        )
        return buildString {
            append("Web container opened: ").append(result.entryPath)
            append("\ncontainerId=").append(result.containerId)
            append("\nstate=foreground")
            if (result.entrySize > 0) append("\nentrySize=").append(result.entrySize).append(" bytes")
            append("\npresentation=").append(result.presentation)
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
    }
}

class WebContainerLifecycleTool(
    private val controller: WebContainerController,
) : SimpleTool<WebContainerLifecycleTool.Args>(
    argsType = typeToken<Args>(),
    name = "manage_web_container",
    description = """
        Manages the lifecycle of Web containers. Actions: list returns every running container; set_state moves one container to foreground or background; reload refreshes its current page after code changes; close stops it.
        containerId is required for set_state, reload, and close. state is required only for set_state and must be foreground or background.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Lifecycle action: list, set_state, reload, or close")
        val action: String,
        @property:LLMDescription("Container ID returned by preview_web_app or the list action")
        val containerId: String? = null,
        @property:LLMDescription("Desired state for set_state: foreground or background")
        val state: String? = null,
    )

    override suspend fun execute(args: Args): String = when (args.action.trim().lowercase()) {
        "list" -> listContainers()
        "set_state" -> {
            val container = controller.setState(
                requiredContainerId(args),
                WebContainerState.fromCode(requireNotNull(args.state?.trim()?.takeIf { it.isNotEmpty() }) {
                    "state is required for set_state"
                }),
            )
            "Web container ${container.id} is now ${container.state.code}."
        }
        "reload" -> {
            val result = controller.interact(
                WebInteractionRequest(
                    containerId = requiredContainerId(args),
                    action = WebInteractionAction.Reload,
                ),
            )
            "Reloaded Web container ${result.containerId}."
        }
        "close" -> {
            val containerId = requiredContainerId(args)
            controller.close(containerId)
            "Closed Web container $containerId."
        }
        else -> error("Invalid Web lifecycle action: ${args.action}. Expected list, set_state, reload, or close")
    }

    private suspend fun listContainers(): String {
        val containers = controller.list()
        if (containers.isEmpty()) return "No Web containers are running."
        return containers.joinToString("\n") { container ->
            "id=${container.id} state=${container.state.code} title=${container.title} entry=${container.entryPath} presentation=${container.presentation}"
        }
    }

    private fun requiredContainerId(args: Args): String = requireNotNull(
        args.containerId?.trim()?.takeIf { it.isNotEmpty() },
    ) { "containerId is required for ${args.action.trim()}" }
}

class WebScreenshotTool(
    private val controller: WebContainerController,
) : SimpleTool<WebScreenshotTool.Args>(
    argsType = typeToken<Args>(),
    name = "screenshot_web_container",
    description = "Captures the visible page of a running Web container and returns the PNG image for visual debugging. Use manage_web_container with action=list when the container ID is unknown.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_web_app or manage_web_container action=list")
        val containerId: String,
    )

    private data class EncodedScreenshot(
        val text: String,
        val bytes: ByteArray,
    )

    private var encodedScreenshot: EncodedScreenshot? = null

    override suspend fun execute(args: Args): String {
        val screenshot = controller.screenshot(args.containerId)
        val text = "Captured Web container ${screenshot.containerId}: ${screenshot.width}x${screenshot.height} PNG (${screenshot.pngBytes.size} bytes)."
        encodedScreenshot = EncodedScreenshot(text, screenshot.pngBytes)
        return text
    }

    override fun encodeResultToParts(result: String, serializer: JSONSerializer): List<MessagePart.ContentPart> {
        val screenshot = encodedScreenshot?.takeIf { it.text == result }
            ?: return listOf(MessagePart.Text(result))
        encodedScreenshot = null
        return listOf(
            MessagePart.Text(result),
            MessagePart.Attachment(
                AttachmentSource.Image(
                    content = AttachmentContent.Binary.Bytes(screenshot.bytes),
                    format = "png",
                    mimeType = "image/png",
                    fileName = "web-container.png",
                ),
            ),
        )
    }
}

class WebInspectContainerTool(
    private val controller: WebContainerController,
) : SimpleTool<WebInspectContainerTool.Args>(
    argsType = typeToken<Args>(),
    name = "inspect_web_container",
    description = "Inspects a running Web page and returns its visible interactive elements with stable handles, accessible names, selectors, and viewport bounds. Use a returned handle with interact_web_container.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_web_app or manage_web_container action=list")
        val containerId: String,
    )

    override suspend fun execute(args: Args): String {
        val page = controller.inspect(args.containerId)
        return buildString {
            append("containerId=").append(page.containerId)
            append("\ntitle=").append(page.title)
            append("\nurl=").append(page.url)
            append("\nviewport=").append(page.viewportWidth).append('x').append(page.viewportHeight)
            if (page.elements.isEmpty()) append("\nNo visible interactive elements.")
            page.elements.forEach { element ->
                append("\nhandle=").append(element.handle)
                append(" tag=").append(element.tag)
                element.role?.let { append(" role=").append(it) }
                append(" name=").append(element.name.replace('\n', ' ').take(200))
                append(" selector=").append(element.selector)
                append(" bounds=").append(element.x).append(',').append(element.y)
                    .append(',').append(element.width).append(',').append(element.height)
                if (element.disabled) append(" disabled=true")
            }
        }
    }
}

class WebInteractContainerTool(
    private val controller: WebContainerController,
) : SimpleTool<WebInteractContainerTool.Args>(
    argsType = typeToken<Args>(),
    name = "interact_web_container",
    description = "Interacts with a running Web page. Supports click (handle, selector, or x/y), input (handle/selector plus text), scroll (deltaX/deltaY), key (key plus optional target), and back. Prefer handles returned by inspect_web_container; use manage_web_container to reload.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_web_app or manage_web_container action=list")
        val containerId: String,
        @property:LLMDescription("One of: click, input, scroll, key, or back")
        val action: String,
        @property:LLMDescription("Stable element handle returned by inspect_web_container")
        val handle: String? = null,
        @property:LLMDescription("CSS selector used when no handle is available")
        val selector: String? = null,
        @property:LLMDescription("Viewport x coordinate for a coordinate click")
        val x: Int? = null,
        @property:LLMDescription("Viewport y coordinate for a coordinate click")
        val y: Int? = null,
        @property:LLMDescription("Text to set for an input action")
        val text: String? = null,
        @property:LLMDescription("Horizontal scroll amount in CSS pixels")
        val deltaX: Int = 0,
        @property:LLMDescription("Vertical scroll amount in CSS pixels")
        val deltaY: Int = 0,
        @property:LLMDescription("DOM key value such as Enter, Escape, ArrowDown, or a single character")
        val key: String? = null,
    )

    override suspend fun execute(args: Args): String {
        val result = controller.interact(
            WebInteractionRequest(
                containerId = args.containerId,
                action = WebInteractionAction.fromCode(args.action.trim()),
                handle = args.handle,
                selector = args.selector,
                x = args.x,
                y = args.y,
                text = args.text,
                deltaX = args.deltaX,
                deltaY = args.deltaY,
                key = args.key,
            ),
        )
        return "Web interaction completed: action=${result.action.code} target=${result.target} containerId=${result.containerId}."
    }
}

class WebConsoleTool(
    private val controller: WebContainerController,
) : SimpleTool<WebConsoleTool.Args>(
    argsType = typeToken<Args>(),
    name = "get_web_console",
    description = "Reads console output and page errors captured from a running Web container. Pass the previous nextCursor to receive only newer entries.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_web_app or manage_web_container action=list")
        val containerId: String,
        @property:LLMDescription("Sequence cursor from the previous result; use 0 for all buffered entries")
        val cursor: Long = 0,
        @property:LLMDescription("Maximum number of entries, between 1 and 200")
        val limit: Int = 100,
    )

    override suspend fun execute(args: Args): String {
        val snapshot = controller.console(args.containerId, args.cursor.coerceAtLeast(0), args.limit.coerceIn(1, 200))
        return buildString {
            append("containerId=").append(snapshot.containerId)
            append("\nnextCursor=").append(snapshot.nextCursor)
            if (snapshot.entries.isEmpty()) append("\nNo new console entries.")
            snapshot.entries.forEach { entry ->
                append("\n[").append(entry.sequence).append("][").append(entry.level).append("] ")
                append(entry.message.replace('\n', ' ').take(4_000))
                entry.source?.let { append(" (").append(it).append(':').append(entry.line ?: 0).append(')') }
            }
        }
    }
}

fun ToolRegistryBuilder.webContainerTools(controller: WebContainerController) {
    tools(
        listOf(
            WebPreviewTool(controller),
            WebContainerLifecycleTool(controller),
            WebScreenshotTool(controller),
            WebInspectContainerTool(controller),
            WebInteractContainerTool(controller),
            WebConsoleTool(controller),
        ),
    )
}
