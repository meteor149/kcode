package ai.meteor.kcode.plugin.overlay

import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.plugin.api.PluginCleanupException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ConversationOverlayOwnershipTest {
    @Test
    fun closeFinishesAllTurnsAndRevokesAllOperations() = runTest {
        val backend = Fixture()
        val controller = OwnedConversationOverlayController(backend)
        val first = controller.startTurn(emptyList())
        val second = controller.startTurn(emptyList())
        first.finish()
        first.finish()
        assertEquals(1, backend.finished)
        controller.close()
        controller.close()
        assertEquals(2, backend.finished)
        assertEquals(1, backend.closed)
        assertFailsWith<IllegalStateException> { second.update(emptyList()) }
        assertFailsWith<IllegalStateException> { second.finish() }
        assertFailsWith<IllegalStateException> { controller.startTurn(emptyList()) }
        assertFailsWith<IllegalStateException> { controller.setHostForeground(false) }
    }

    @Test
    fun withdrawalCancelsUpdateAndWaitsForCleanupBeforeFinishingTurns() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Fixture(onUpdate = {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        })
        val controller = OwnedConversationOverlayController(backend)
        val turn = controller.startTurn(emptyList())
        val updating = backgroundScope.async { turn.update(emptyList()) }
        entered.await()
        val closing = async { controller.close() }
        cleaning.await()
        assertFalse(closing.isCompleted)
        assertEquals(0, backend.closed)
        assertEquals(0, backend.finished)
        release.complete(Unit)
        closing.await()
        updating.join()
        assertTrue(updating.isCancelled)
        assertEquals(1, backend.finished)
        assertEquals(1, backend.closed)
    }

    @Test
    fun canceledAllocationFinishesLateTurnBeforeReturning() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Fixture(onStart = { entered.complete(Unit); release.await() })
        val controller = OwnedConversationOverlayController(backend)
        val allocating = backgroundScope.async { controller.startTurn(emptyList()) }
        entered.await()
        allocating.cancel()
        val closing = async { controller.close() }
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        allocating.join()
        closing.await()
        assertEquals(1, backend.finished)
        assertEquals(1, backend.closed)
    }

    @Test
    fun cleanupFailureStillClosesEveryTurnAndBackendAndIsCached() = runTest {
        val backend = Fixture(finishFailure = IllegalArgumentException("turn failure"))
        val controller = OwnedConversationOverlayController(backend)
        controller.startTurn(emptyList())
        controller.startTurn(emptyList())
        val first = assertFailsWith<PluginCleanupException> { controller.close() }
        assertEquals(2, first.failures.size)
        assertEquals(2, backend.finished)
        assertEquals(1, backend.closed)
        val second = assertFailsWith<PluginCleanupException> { controller.close() }
        assertEquals(first.failures, second.failures)
        assertEquals(1, backend.closed)
    }

    @Test
    fun concurrentCloseWaitsForSameCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Fixture(onClose = { entered.complete(Unit); release.await() })
        val controller = OwnedConversationOverlayController(backend)
        val first = async { controller.close() }
        entered.await()
        val second = async { controller.close() }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, backend.closed)
    }

    @Test
    fun selfWithdrawalFailsBeforeChangingProviderState() = runTest {
        lateinit var controller: OwnedConversationOverlayController
        val backend = Fixture(onUpdate = { controller.close() })
        controller = OwnedConversationOverlayController(backend)
        val turn = controller.startTurn(emptyList())
        assertFailsWith<IllegalStateException> { turn.update(emptyList()) }
        controller.setHostForeground(false)
        assertEquals(listOf(false), backend.foregrounds)
        assertEquals(0, backend.closed)
        controller.close()
        assertEquals(1, backend.closed)
    }

    private class Fixture(
        val onStart: suspend () -> Unit = {},
        val onUpdate: suspend () -> Unit = {},
        val onClose: suspend () -> Unit = {},
        val finishFailure: Throwable? = null,
    ) : AgentConversationOverlayController {
        var finished = 0
        var closed = 0
        val foregrounds = mutableListOf<Boolean>()
        override suspend fun setHostForeground(isForeground: Boolean) { foregrounds += isForeground }
        override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn {
            onStart()
            return object : AgentConversationOverlayTurn {
                override suspend fun update(messages: List<ChatMessage>) = onUpdate()
                override suspend fun finish() { finished++; finishFailure?.let { throw it } }
            }
        }
        override suspend fun close() { closed++; onClose() }
    }
}
