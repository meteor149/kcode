package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.plugin.api.PluginOperationOwner
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
import kotlinx.coroutines.yield

class OwnedHistoryRepositoryTest {
    @Test
    fun historyDisposalWaitsForTransactionCleanupAndRejectsStaleReads() = runTest {
        val owner = PluginOperationOwner("history")
        val gate = CleanupGate()
        val history = OwnedHistoryRepository(object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun nextConversationId(): Long = 101L
            override suspend fun setStandaloneResult(conversationId: Long, result: String) = gate.waitForCancellation()
        }, owner)
        assertEquals(101L, history.nextConversationId())
        exerciseDisposal(owner, gate, { history.setStandaloneResult(101, "result") }, { history.loadScheduledTasks() })
        assertFailsWith<IllegalStateException> { history.createConversation(1, "old") }
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
