package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import ai.meteor.kcode.h5.H5ContainerController
import ai.meteor.kcode.h5.H5ContainerState
import ai.meteor.kcode.h5.H5InteractionAction
import ai.meteor.kcode.h5.H5InteractionRequest
import ai.meteor.kcode.h5.H5PreviewRequest
import kotlinx.serialization.Serializable

class H5PreviewTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5PreviewTool.Args>(
    argsType = typeToken<Args>(),
    name = "preview_h5_app",
    description = """
        Opens and runs a browser-ready H5 application created in the platform's /workspace directory.
        Write the HTML, CSS, JavaScript, images, and other assets first, then call this tool with the HTML entry path.
        Relative resources, JavaScript, ES modules, and browser APIs are supported. The preview cannot access files outside /workspace.
        Use this tool instead of starting a local HTTP server yourself.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute HTML entry path inside /workspace, for example /workspace/my-app/index.html")
        val entryPath: String = "/workspace/index.html",
        @property:LLMDescription("Short title shown by the preview container")
        val title: String = "H5 Preview",
    )

    override suspend fun execute(args: Args): String {
        val result = controller.launch(
            H5PreviewRequest(
                entryPath = args.entryPath,
                title = args.title.trim().take(MAX_TITLE_LENGTH).ifBlank { "H5 Preview" },
            ),
        )
        return buildString {
            append("H5 preview opened: ").append(result.entryPath)
            append("\ncontainerId=").append(result.containerId)
            append("\nstate=foreground")
            append("\nentrySize=").append(result.entrySize).append(" bytes")
            append("\npresentation=").append(result.presentation)
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
    }
}

class H5ListContainersTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5ListContainersTool.Args>(
    argsType = typeToken<Args>(),
    name = "list_h5_containers",
    description = "Lists the H5 preview containers that are currently running. Use the returned container ID for screenshots or closing a preview.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String {
        val containers = controller.list()
        if (containers.isEmpty()) return "No H5 containers are running."
        return containers.joinToString("\n") { container ->
            "id=${container.id} state=${container.state.code} title=${container.title} entry=${container.entryPath} presentation=${container.presentation}"
        }
    }
}

class H5SetContainerStateTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5SetContainerStateTool.Args>(
    argsType = typeToken<Args>(),
    name = "set_h5_container_state",
    description = "Moves a running H5 preview to the foreground or background without stopping it. Use foreground when the user should see or interact with it, and background while editing or running other tools.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_h5_app or list_h5_containers")
        val containerId: String,
        @property:LLMDescription("Desired state: foreground or background")
        val state: String,
    )

    override suspend fun execute(args: Args): String {
        val container = controller.setState(args.containerId, H5ContainerState.fromCode(args.state.trim()))
        return "H5 container ${container.id} is now ${container.state.code}."
    }
}

class H5ScreenshotTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5ScreenshotTool.Args>(
    argsType = typeToken<Args>(),
    name = "screenshot_h5_container",
    description = "Captures the visible page of a running H5 preview and returns the PNG image for visual debugging. Call list_h5_containers first when the container ID is unknown.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_h5_app or list_h5_containers")
        val containerId: String,
    )

    private data class EncodedScreenshot(
        val text: String,
        val bytes: ByteArray,
    )

    private var encodedScreenshot: EncodedScreenshot? = null

    override suspend fun execute(args: Args): String {
        val screenshot = controller.screenshot(args.containerId)
        val text = "Captured H5 container ${screenshot.containerId}: ${screenshot.width}x${screenshot.height} PNG (${screenshot.pngBytes.size} bytes)."
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
                    fileName = "h5-container.png",
                ),
            ),
        )
    }
}

class H5InspectContainerTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5InspectContainerTool.Args>(
    argsType = typeToken<Args>(),
    name = "inspect_h5_container",
    description = "Inspects a running H5 page and returns its visible interactive elements with stable handles, accessible names, selectors, and viewport bounds. Use a returned handle with interact_h5_container.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_h5_app or list_h5_containers")
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

class H5InteractContainerTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5InteractContainerTool.Args>(
    argsType = typeToken<Args>(),
    name = "interact_h5_container",
    description = "Interacts with a running H5 page. Supports click (handle, selector, or x/y), input (handle/selector plus text), scroll (deltaX/deltaY), key (key plus optional target), reload, and back. Prefer handles returned by inspect_h5_container.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_h5_app or list_h5_containers")
        val containerId: String,
        @property:LLMDescription("One of: click, input, scroll, key, reload, back")
        val action: String,
        @property:LLMDescription("Stable element handle returned by inspect_h5_container")
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
            H5InteractionRequest(
                containerId = args.containerId,
                action = H5InteractionAction.fromCode(args.action.trim()),
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
        return "H5 interaction completed: action=${result.action.code} target=${result.target} containerId=${result.containerId}."
    }
}

class H5ConsoleTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5ConsoleTool.Args>(
    argsType = typeToken<Args>(),
    name = "get_h5_console",
    description = "Reads console output and page errors captured from a running H5 container. Pass the previous nextCursor to receive only newer entries.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_h5_app or list_h5_containers")
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

class H5CloseContainerTool(
    private val controller: H5ContainerController,
) : SimpleTool<H5CloseContainerTool.Args>(
    argsType = typeToken<Args>(),
    name = "close_h5_container",
    description = "Stops and closes one running H5 preview container. Call list_h5_containers first when the container ID is unknown.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("ID returned by preview_h5_app or list_h5_containers")
        val containerId: String,
    )

    override suspend fun execute(args: Args): String {
        controller.close(args.containerId)
        return "Closed H5 container ${args.containerId}."
    }
}
