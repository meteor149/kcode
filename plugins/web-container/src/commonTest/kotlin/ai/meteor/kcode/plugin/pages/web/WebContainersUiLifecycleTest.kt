package ai.meteor.kcode.plugin.pages.web

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebContainersUiLifecycleTest {
    @Test
    fun disposalJoinsPollingAndRestoreCleanupAndRejectsStaleActions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val restoring = CompletableDeferred<Unit>()
        val pollingCleanup = CompletableDeferred<Unit>()
        val restoreCleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun blocked(start: CompletableDeferred<Unit>, cleanup: CompletableDeferred<Unit>): Nothing {
            start.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanup.complete(Unit)
                    release.await()
                }
            }
        }
        val controller = object : UnusedWebController() {
            override suspend fun list(): List<WebContainerInfo> = blocked(entered, pollingCleanup)
            override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo =
                blocked(restoring, restoreCleanup)
        }
        val actions = WebContainersUiActions(controller)
        val polling = launch { actions.monitor { error("Must not publish") } }
        val restore = launch { actions.restore("id") }
        entered.await()
        restoring.await()
        val closing = launch { actions.close() }
        pollingCleanup.await()
        restoreCleanup.await()
        assertFalse(closing.isCompleted)
        assertFalse(actions.isAvailable)
        release.complete(Unit)
        closing.join()
        polling.join()
        restore.join()
        assertTrue(polling.isCancelled)
        assertTrue(restore.isCancelled)
        assertFailsWith<IllegalStateException> { actions.monitor {} }
        assertFailsWith<IllegalStateException> { actions.backgroundContainers() }
        assertFailsWith<IllegalStateException> { actions.restore("id") }
        assertFailsWith<IllegalStateException> { actions.closeContainer("id") }
    }
}

private open class UnusedWebController : WebContainerController {
    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = error("Unused")
    override suspend fun list(): List<WebContainerInfo> = error("Unused")
    override suspend fun screenshot(containerId: String): WebContainerScreenshot = error("Unused")
    override suspend fun inspect(containerId: String): WebPageInspection = error("Unused")
    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = error("Unused")
    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot = error("Unused")
    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo = error("Unused")
    override suspend fun close(containerId: String): Unit = error("Unused")
}
