package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebConsoleEntry
import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebInteractiveElement
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class WebContainerToolsTest {
    @Test
    fun registersCompleteWebToolSetThroughOneEntryPoint() {
        val registry = ToolRegistry {
            webContainerTools(FakeWebContainerController())
        }

        assertEquals(
            listOf(
                "preview_web_app",
                "manage_web_container",
                "screenshot_web_container",
                "inspect_web_container",
                "interact_web_container",
                "get_web_console",
            ),
            registry.tools.map { it.name },
        )
    }

    @Test
    fun toolsExposeLifecycleAndScreenshotAsImageContent() = runBlocking {
        val controller = FakeWebContainerController()
        val preview = WebPreviewTool(controller).execute(
            WebPreviewTool.Args("/workspace/demo/index.html", "Demo"),
        )
        assertContains(preview, "containerId=container-1")

        val remotePreview = WebPreviewTool(controller).execute(
            WebPreviewTool.Args(title = "Remote", url = "https://example.com/app"),
        )
        assertContains(remotePreview, "https://example.com/app")
        assertEquals("https://example.com/app", controller.list().single().entryPath)

        val lifecycle = WebContainerLifecycleTool(controller)
        val listing = lifecycle.execute(WebContainerLifecycleTool.Args(action = "list"))
        assertContains(listing, "id=container-1")
        assertContains(listing, "state=foreground")

        val stateResult = lifecycle.execute(
            WebContainerLifecycleTool.Args("set_state", "container-1", "background"),
        )
        assertContains(stateResult, "now background")

        val screenshotTool = WebScreenshotTool(controller)
        val screenshotText = screenshotTool.execute(WebScreenshotTool.Args("container-1"))
        val parts = screenshotTool.encodeResultToParts(screenshotText, KotlinxSerializer())
        assertIs<MessagePart.Text>(parts[0])
        val image = assertIs<AttachmentSource.Image>(assertIs<MessagePart.Attachment>(parts[1]).source)
        val binary = assertIs<AttachmentContent.Binary.Bytes>(image.content)
        assertContentEquals(byteArrayOf(1, 2, 3), binary.data)

        val inspection = WebInspectContainerTool(controller).execute(WebInspectContainerTool.Args("container-1"))
        assertContains(inspection, "handle=element-1")
        val interaction = WebInteractContainerTool(controller).execute(
            WebInteractContainerTool.Args("container-1", "click", handle = "element-1"),
        )
        assertContains(interaction, "action=click")
        val console = WebConsoleTool(controller).execute(WebConsoleTool.Args("container-1"))
        assertContains(console, "[1][log] clicked")

        val reload = lifecycle.execute(WebContainerLifecycleTool.Args("reload", "container-1"))
        assertEquals("Reloaded Web container container-1.", reload)

        lifecycle.execute(WebContainerLifecycleTool.Args("close", "container-1"))
        assertEquals(emptyList(), controller.list())
    }

    @Test
    fun previewRequiresExactlyOneSource() = runBlocking {
        val tool = WebPreviewTool(FakeWebContainerController())
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            tool.execute(WebPreviewTool.Args())
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            tool.execute(WebPreviewTool.Args("/workspace/index.html", url = "https://example.com"))
        }
        Unit
    }

    private class FakeWebContainerController : WebContainerController {
        private var active: WebContainerInfo? = null

        override suspend fun launch(request: WebPreviewRequest): WebPreviewResult {
            active = WebContainerInfo(
                "container-1",
                request.entryPath,
                request.title,
                "test",
                WebContainerState.Foreground,
            )
            return WebPreviewResult("container-1", request.entryPath, 42, "test")
        }

        override suspend fun list(): List<WebContainerInfo> = listOfNotNull(active)

        override suspend fun screenshot(containerId: String): WebContainerScreenshot {
            require(active?.id == containerId)
            return WebContainerScreenshot(containerId, byteArrayOf(1, 2, 3), 320, 480)
        }

        override suspend fun inspect(containerId: String): WebPageInspection {
            require(active?.id == containerId)
            return WebPageInspection(
                containerId,
                "file:///workspace/demo/index.html",
                "Demo",
                320,
                480,
                listOf(WebInteractiveElement("element-1", "button", null, "Run", "[data-kcode-handle=element-1]", 1, 2, 40, 20, false)),
            )
        }

        override suspend fun interact(request: WebInteractionRequest): WebInteractionResult {
            require(active?.id == request.containerId)
            return WebInteractionResult(request.containerId, request.action, request.handle ?: "page")
        }

        override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot {
            require(active?.id == containerId)
            val entries = listOf(WebConsoleEntry(1, "log", "clicked")).filter { it.sequence > cursor }.take(limit)
            return WebConsoleSnapshot(containerId, entries, entries.lastOrNull()?.sequence ?: cursor)
        }

        override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo {
            require(active?.id == containerId)
            return requireNotNull(active).copy(state = state).also { active = it }
        }

        override suspend fun close(containerId: String) {
            require(active?.id == containerId)
            active = null
        }
    }
}
