package ai.meteor.kcode.plugin.pages.artifact

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactType

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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
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

    @Test
    fun pageWithdrawalJoinsListingCleanupAndRejectsRetainedActions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var slow = true
        val repository = object : ai.meteor.kcode.artifact.ArtifactRepository {
            override suspend fun list(): List<Artifact> {
                if (!slow) return emptyList()
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val actions = ArtifactPageActions()
        val listing = backgroundScope.async { actions.list(repository) }
        try {
            entered.await()
            val withdrawing = async { actions.close() }
            cleaning.await()
            assertFalse(withdrawing.isCompleted)
            assertFailsWith<IllegalStateException> { actions.list(repository) }
            release.complete(Unit)
            withdrawing.await()
            listing.join()
            assertFailsWith<IllegalStateException> { actions.list(repository) }
            slow = false
            assertEquals(emptyList(), repository.list())
        } finally { release.complete(Unit); actions.close() }
    }

    @Test
    fun pageWithdrawalRejectsLaunchWithoutClosingTheBorrowedController() = runTest {
        val controller = RecordingController()
        val artifact = Artifact("demo", "Demo", ArtifactType.WebApp, "demo", "index.html", "")
        val actions = ArtifactPageActions()
        actions.close()
        assertFailsWith<IllegalStateException> { actions.open(controller, artifact) }
        assertNull(controller.launched)
        ArtifactLauncher(controller).open(artifact)
        assertEquals(artifact.entryPath, controller.launched?.entryPath)
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
