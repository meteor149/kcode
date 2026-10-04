package ai.meteor.kcode.plugin

import ai.meteor.kcode.RootAgentPath
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
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
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

class OwnedSubagentFactoryTest {
    @Test
    fun providerDisposalWaitsForChildCleanupAndRejectsOldFactoryAndCoordinator() = runTest {
        val factory = OwnedSubagentFactory()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = factory.create(backgroundScope, "root", {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        }, {})
        try {
            coordinator.spawn(RootAgentPath, "worker", "task", null)
            entered.await()
            val closing = async { factory.close() }
            cleaning.await()
            assertFalse(closing.isCompleted)
            assertFailsWith<IllegalStateException> { coordinator.list(RootAgentPath, null) }
            assertFailsWith<IllegalStateException> { factory.create(backgroundScope, "old", { "unused" }, {}) }
            release.complete(Unit)
            closing.await()
            assertFailsWith<IllegalStateException> { coordinator.spawn(RootAgentPath, "old", "task", null) }
            coordinator.shutdown()
        } finally { release.complete(Unit); factory.close() }
    }

    @Test
    fun normalCoordinatorShutdownInvalidatesOnlyThatSessionAndAllowsAnother() = runTest {
        val factory = OwnedSubagentFactory()
        val first = factory.create(backgroundScope, "first", { "answer" }, {})
        first.shutdown()
        assertFailsWith<IllegalStateException> { first.drainMailbox(RootAgentPath) }
        val second = factory.create(backgroundScope, "second", { "answer" }, {})
        assertTrue(second.list(RootAgentPath, null).contains("No matching agents"))
        factory.close()
        assertFailsWith<IllegalStateException> { second.list(RootAgentPath, null) }
    }
    @Test
    fun failedCallCleanupStillClosesChildrenAndRetainsTheFactoryFailure() = runTest {
        supervisorScope {
            val factory = OwnedSubagentFactory()
            val childEntered = CompletableDeferred<Unit>()
            val callbackEntered = CompletableDeferred<Unit>()
            val childCleaning = CompletableDeferred<Unit>()
            val releaseChild = CompletableDeferred<Unit>()
            val failure = IllegalStateException("callback cleanup failed")
            var childClosed = false
            val coordinator = factory.create(backgroundScope, "root", {
                childEntered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        childCleaning.complete(Unit)
                        releaseChild.await()
                        childClosed = true
                    }
                }
            }, { event ->
                if (event is SubAgentEvent.StatusChanged && event.currentTool == "blocked") {
                    callbackEntered.complete(Unit)
                    try { awaitCancellation() } finally { throw failure }
                }
            })
            coordinator.spawn(RootAgentPath, "worker", "task", null)
            childEntered.await()
            val callback = async {
                coordinator.onToolUse("$RootAgentPath/worker", ToolUseEvent.Started("id", "blocked", "{}"))
            }
            callbackEntered.await()
            val closing = async { runCatching { factory.close() }.exceptionOrNull() }
            childCleaning.await()
            assertFalse(closing.isCompleted)
            releaseChild.complete(Unit)
            val error = closing.await()!!
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === failure })
            assertTrue(childClosed)
            callback.join()
            val repeated = assertFailsWith<IllegalStateException> { factory.close() }
            assertTrue(generateSequence<Throwable>(repeated) { it.cause }.any { it === failure })
            assertFailsWith<IllegalStateException> { coordinator.shutdown() }
            assertFailsWith<IllegalStateException> { coordinator.list(RootAgentPath, null) }
        }
    }

    @Test
    fun concurrentFactoryWithdrawalWaitsForTheSameChildCleanup() = runTest {
        val factory = OwnedSubagentFactory()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val coordinator = factory.create(backgroundScope, "root", {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                    cleanupCount += 1
                }
            }
        }, {})
        coordinator.spawn(RootAgentPath, "worker", "task", null)
        entered.await()
        val first = async { factory.close() }
        cleaning.await()
        val second = async { factory.close() }
        yield()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, cleanupCount)
        factory.close()
    }

    @Test
    fun callbackCannotWithdrawItsFactoryOrInvalidateOtherCoordinators() = runTest {
        val factory = OwnedSubagentFactory()
        var rejected = 0
        val coordinator = factory.create(backgroundScope, "root", { awaitCancellation() }, { event ->
            if (event is SubAgentEvent.StatusChanged && event.currentTool == "self") {
                assertFailsWith<IllegalStateException> { factory.close() }
                rejected += 1
            }
        })
        try {
            coordinator.spawn(RootAgentPath, "worker", "task", null)
            yield()
            coordinator.onToolUse("$RootAgentPath/worker", ToolUseEvent.Started("id", "self", "{}"))
            assertEquals(1, rejected)
            assertTrue(coordinator.list(RootAgentPath, null).contains("worker"))
            val another = factory.create(backgroundScope, "other", { "answer" }, {})
            assertTrue(another.list(RootAgentPath, null).contains("No matching agents"))
        } finally {
            factory.close()
        }
    }

}
