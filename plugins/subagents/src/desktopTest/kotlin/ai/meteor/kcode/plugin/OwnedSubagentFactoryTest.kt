package ai.meteor.kcode.plugin

import ai.meteor.kcode.RootAgentPath
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.plugin.api.ExecutionAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
    fun closedAdmissionRejectsCoordinatorPreparationBeforeCallbacksAndRecoversWithoutPhantomAgents() = runTest {
        var open = false
        var events = 0
        var runs = 0
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (!open) throw CancellationException("Preparing Profile")
                return block()
            }
        }
        val factory = OwnedSubagentFactory(admission = admission)
        val coordinator = factory.create(backgroundScope, "root", { runs++; "answer" }, { events++ })
        try {
            assertFailsWith<CancellationException> { coordinator.spawn(RootAgentPath, "worker", "task", null) }
            assertFailsWith<CancellationException> { coordinator.followupTask(RootAgentPath, "worker", "task") }
            assertFailsWith<CancellationException> { coordinator.sendMessage(RootAgentPath, "worker", "message") }
            assertFailsWith<CancellationException> { coordinator.waitForUpdate(RootAgentPath) }
            assertFailsWith<CancellationException> { coordinator.drainMailbox(RootAgentPath) }
            assertEquals(0, events)
            assertEquals(0, runs)
            open = true
            assertTrue(coordinator.list(RootAgentPath, null).contains("No matching agents"))
            coordinator.spawn(RootAgentPath, "worker", "retry", null)
            testScheduler.runCurrent()
            assertEquals(1, runs)
            assertTrue(coordinator.list(RootAgentPath, null).contains("completed"))
        } finally { factory.close() }
    }

    @Test
    fun childHandoffDeniedAfterSpawnReleasesItsSlotWithoutExecutingCallbacks() = runTest {
        var open = true
        var runs = 0
        val events = mutableListOf<SubAgentEvent>()
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (!open) throw CancellationException("Handoff paused")
                return block()
            }
        }
        val factory = OwnedSubagentFactory(2, admission)
        val coordinator = factory.create(backgroundScope, "root", { runs++; "answer" }, events::add)
        try {
            coordinator.spawn(RootAgentPath, "worker", "task", null)
            open = false
            testScheduler.runCurrent()
            assertEquals(0, runs)
            assertEquals(1, events.size)
            assertTrue(events.single() is SubAgentEvent.Spawned)
            open = true
            assertTrue(coordinator.list(RootAgentPath, null).contains("interrupted"))
            coordinator.followupTask(RootAgentPath, "worker", "retry")
            testScheduler.runCurrent()
            assertEquals(1, runs)
            assertTrue(coordinator.list(RootAgentPath, null).contains("completed"))
        } finally { factory.close() }
    }

    @Test
    fun detachedCoordinatorHoldsAdmissionThroughStructuredChildAndCancellationCleanup() = runTest {
        val admitted = mutableSetOf<Job>()
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                val job = checkNotNull(currentCoroutineContext()[Job])
                admitted += job
                try { return block() } finally { admitted -= job }
            }
        }
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = OwnedSubagentFactory(admission = admission)
        val coordinator = factory.create(backgroundScope, "root", {
            CoroutineScope(currentCoroutineContext()).launch {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            "parent returned"
        }, {})
        try {
            coordinator.spawn(RootAgentPath, "worker", "task", null)
            entered.await()
            assertEquals(1, admitted.size)
            assertTrue(coordinator.list(RootAgentPath, null).contains("running"))
            val closing = async { factory.close() }
            cleaning.await()
            assertFalse(closing.isCompleted)
            assertEquals(1, admitted.size)
            release.complete(Unit)
            closing.await()
            assertTrue(admitted.isEmpty())
        } finally { release.complete(Unit); factory.close() }
    }

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
