package ai.meteor.kcode.plugin.webcontainer

import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class OwnedWebContainersTest {
    @Test
    fun webDisposalClosesResourcesAfterCallsAndWaitsForContainerCleanup() = runTest {
        val owner = PluginOperationOwner("web")
        val gate = CleanupGate()
        val resourceCleanupStarted = CompletableDeferred<Unit>()
        val releaseResources = CompletableDeferred<Unit>()
        var resourcesClosed = false
        val controller = OwnedWebContainerController(object : TestWebController() {
            override suspend fun list(): List<WebContainerInfo> { gate.waitForCancellation(); error("Unreachable") }
            override suspend fun closeAll() {
                assertTrue(gate.releaseCleanup.isCompleted)
                resourceCleanupStarted.complete(Unit)
                releaseResources.await()
                resourcesClosed = true
            }
        }, owner)
        val call = async { controller.list() }
        gate.entered.await()
        val disposing = async { controller.dispose() }
        gate.cleanupStarted.await()
        assertFalse(resourceCleanupStarted.isCompleted)
        gate.releaseCleanup.complete(Unit)
        resourceCleanupStarted.await()
        assertFalse(disposing.isCompleted)
        assertFailsWith<IllegalStateException> { controller.closeAll() }
        releaseResources.complete(Unit)
        disposing.await()
        call.join()
        assertTrue(resourcesClosed)
        assertTrue(call.isCancelled)
    }

    @Test
    fun defaultWebResourceCleanupAttemptsEveryContainerWhenOneCloseFails() = runTest {
        val attempted = mutableListOf<String>()
        val controller = object : TestWebController() {
            override suspend fun list() = listOf("first", "second", "third").map {
                WebContainerInfo(it, "/workspace/index.html", "test", "test", WebContainerState.Background)
            }
            override suspend fun close(containerId: String) {
                attempted += containerId
                if (containerId != "second") error(containerId)
            }
        }
        val error = assertFailsWith<IllegalStateException> { controller.closeAll() }
        assertEquals(listOf("first", "second", "third"), attempted)
        assertEquals("first", error.message)
        assertEquals("third", error.suppressedExceptions.single().message)
    }

    @Test
    fun webControllerDisposalWaitsForCallCleanupAndRejectsStaleClose() = runTest {
        val owner = PluginOperationOwner("web")
        val gate = CleanupGate()
        val controller = OwnedWebContainerController(object : WebContainerController {
            override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = error("Unused")
            override suspend fun list(): List<WebContainerInfo> { gate.waitForCancellation(); error("Unreachable") }
            override suspend fun screenshot(containerId: String): WebContainerScreenshot = error("Unused")
            override suspend fun inspect(containerId: String): WebPageInspection = error("Unused")
            override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = error("Unused")
            override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot = error("Unused")
            override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo = error("Unused")
            override suspend fun close(containerId: String) = error("Delegate must not be called after disposal")
        }, owner)
        exerciseDisposal(owner, gate, { controller.list() }, { controller.close("stale") })
    }
    @Test
    fun webDisposalStillClosesContainersWhenCallCleanupFails() = runTest {
        supervisorScope {
            val owner = PluginOperationOwner("web")
            val entered = CompletableDeferred<Unit>()
            val callbackFailure = IllegalStateException("call cleanup failed")
            val resourceFailure = IllegalStateException("container cleanup failed")
            var resourceAttempts = 0
            val controller = OwnedWebContainerController(object : TestWebController() {
                override suspend fun list(): List<WebContainerInfo> {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { throw callbackFailure }
                }
                override suspend fun closeAll() {
                    resourceAttempts += 1
                    throw resourceFailure
                }
            }, owner)
            val running = async { controller.list() }
            entered.await()
            val error = assertFailsWith<PluginCleanupException> { controller.dispose() }
            assertEquals(2, error.failures.size)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === callbackFailure })
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any {
                it.suppressedExceptions.any { suppressed ->
                    generateSequence<Throwable>(suppressed) { it.cause }.any { it === resourceFailure }
                }
            })
            assertEquals(1, resourceAttempts)
            running.join()
            assertFailsWith<IllegalStateException> { controller.list() }
        }
    }

}

private class CleanupGate {
    val entered = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()

    suspend fun waitForCancellation() {
        entered.complete(Unit)
        try { awaitCancellation() } finally {
            withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            }
        }
    }
}

private suspend fun kotlinx.coroutines.CoroutineScope.exerciseDisposal(
    owner: PluginOperationOwner,
    gate: CleanupGate,
    call: suspend () -> Unit,
    staleCall: suspend () -> Unit,
) {
    val running = async { call() }
    gate.entered.await()
    val closing = async { owner.close() }
    gate.cleanupStarted.await()
    yield()
    assertFalse(closing.isCompleted)
    assertFailsWith<IllegalStateException> { staleCall() }
    gate.releaseCleanup.complete(Unit)
    closing.await()
    running.join()
    assertTrue(running.isCancelled)
    assertFailsWith<IllegalStateException> { staleCall() }
}

private open class TestWebController : WebContainerController {
    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = error("Unused")
    override suspend fun list(): List<WebContainerInfo> = emptyList()
    override suspend fun screenshot(containerId: String): WebContainerScreenshot = error("Unused")
    override suspend fun inspect(containerId: String): WebPageInspection = error("Unused")
    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = error("Unused")
    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot = error("Unused")
    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo = error("Unused")
    override suspend fun close(containerId: String) = Unit
}
