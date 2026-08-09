package ai.meteor.kcode.artifact

import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ArtifactLauncherTest {
    @Test
    fun opensWebArtifactThroughWebContainer() = runTest {
        val controller = RecordingController()
        val artifact = Artifact(
            id = "demo",
            name = "Demo",
            type = ArtifactType.WebApp,
            directory = "demo",
            entryPoint = "index.html",
            description = "",
        )

        ArtifactLauncher(controller).open(artifact)

        assertEquals("/workspace/artifacts/resources/demo/index.html", controller.launched?.entryPath)
        assertEquals("Demo", controller.launched?.title)
    }

    private class RecordingController : WebContainerController {
        var launched: WebPreviewRequest? = null

        override suspend fun launch(request: WebPreviewRequest): WebPreviewResult {
            launched = request
            return WebPreviewResult("id", request.entryPath, 1L, "test")
        }

        override suspend fun list(): List<WebContainerInfo> = emptyList()
        override suspend fun screenshot(containerId: String): WebContainerScreenshot = error("Not used")
        override suspend fun inspect(containerId: String): WebPageInspection = error("Not used")
        override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = error("Not used")
        override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot = error("Not used")
        override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo = error("Not used")
        override suspend fun close(containerId: String) = Unit
    }
}
