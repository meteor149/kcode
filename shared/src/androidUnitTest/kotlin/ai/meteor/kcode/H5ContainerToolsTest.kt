package ai.meteor.kcode

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.meteor.kcode.h5.H5ContainerController
import ai.meteor.kcode.h5.H5ContainerInfo
import ai.meteor.kcode.h5.H5ContainerScreenshot
import ai.meteor.kcode.h5.H5ContainerState
import ai.meteor.kcode.h5.H5ConsoleEntry
import ai.meteor.kcode.h5.H5ConsoleSnapshot
import ai.meteor.kcode.h5.H5InteractionRequest
import ai.meteor.kcode.h5.H5InteractionResult
import ai.meteor.kcode.h5.H5InteractiveElement
import ai.meteor.kcode.h5.H5PageInspection
import ai.meteor.kcode.h5.H5PreviewRequest
import ai.meteor.kcode.h5.H5PreviewResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class H5ContainerToolsTest {
    @Test
    fun toolsExposeLifecycleAndScreenshotAsImageContent() = runBlocking {
        val controller = FakeH5Controller()
        val preview = H5PreviewTool(controller).execute(
            H5PreviewTool.Args("/workspace/demo/index.html", "Demo"),
        )
        assertContains(preview, "containerId=container-1")

        val listing = H5ListContainersTool(controller).execute(H5ListContainersTool.Args())
        assertContains(listing, "id=container-1")
        assertContains(listing, "state=foreground")

        val stateResult = H5SetContainerStateTool(controller).execute(
            H5SetContainerStateTool.Args("container-1", "background"),
        )
        assertContains(stateResult, "now background")

        val screenshotTool = H5ScreenshotTool(controller)
        val screenshotText = screenshotTool.execute(H5ScreenshotTool.Args("container-1"))
        val parts = screenshotTool.encodeResultToParts(screenshotText, KotlinxSerializer())
        assertIs<MessagePart.Text>(parts[0])
        val image = assertIs<AttachmentSource.Image>(assertIs<MessagePart.Attachment>(parts[1]).source)
        val binary = assertIs<AttachmentContent.Binary.Bytes>(image.content)
        assertContentEquals(byteArrayOf(1, 2, 3), binary.data)

        val inspection = H5InspectContainerTool(controller).execute(H5InspectContainerTool.Args("container-1"))
        assertContains(inspection, "handle=element-1")
        val interaction = H5InteractContainerTool(controller).execute(
            H5InteractContainerTool.Args("container-1", "click", handle = "element-1"),
        )
        assertContains(interaction, "action=click")
        val console = H5ConsoleTool(controller).execute(H5ConsoleTool.Args("container-1"))
        assertContains(console, "[1][log] clicked")

        H5CloseContainerTool(controller).execute(H5CloseContainerTool.Args("container-1"))
        assertEquals(emptyList(), controller.list())
    }

    private class FakeH5Controller : H5ContainerController {
        private var active: H5ContainerInfo? = null

        override suspend fun launch(request: H5PreviewRequest): H5PreviewResult {
            active = H5ContainerInfo(
                "container-1",
                request.entryPath,
                request.title,
                "test",
                H5ContainerState.Foreground,
            )
            return H5PreviewResult("container-1", request.entryPath, 42, "test")
        }

        override suspend fun list(): List<H5ContainerInfo> = listOfNotNull(active)

        override suspend fun screenshot(containerId: String): H5ContainerScreenshot {
            require(active?.id == containerId)
            return H5ContainerScreenshot(containerId, byteArrayOf(1, 2, 3), 320, 480)
        }

        override suspend fun inspect(containerId: String): H5PageInspection {
            require(active?.id == containerId)
            return H5PageInspection(
                containerId,
                "file:///workspace/demo/index.html",
                "Demo",
                320,
                480,
                listOf(H5InteractiveElement("element-1", "button", null, "Run", "[data-kcode-handle=element-1]", 1, 2, 40, 20, false)),
            )
        }

        override suspend fun interact(request: H5InteractionRequest): H5InteractionResult {
            require(active?.id == request.containerId)
            return H5InteractionResult(request.containerId, request.action, request.handle ?: "page")
        }

        override suspend fun console(containerId: String, cursor: Long, limit: Int): H5ConsoleSnapshot {
            require(active?.id == containerId)
            val entries = listOf(H5ConsoleEntry(1, "log", "clicked")).filter { it.sequence > cursor }.take(limit)
            return H5ConsoleSnapshot(containerId, entries, entries.lastOrNull()?.sequence ?: cursor)
        }

        override suspend fun setState(containerId: String, state: H5ContainerState): H5ContainerInfo {
            require(active?.id == containerId)
            return requireNotNull(active).copy(state = state).also { active = it }
        }

        override suspend fun close(containerId: String) {
            require(active?.id == containerId)
            active = null
        }
    }
}
