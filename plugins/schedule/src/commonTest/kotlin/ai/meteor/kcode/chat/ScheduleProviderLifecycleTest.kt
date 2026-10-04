package ai.meteor.kcode.chat

import ai.meteor.kcode.plugin.schedule.HistoryScheduledTaskCoordinator
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.test.EmptyHistoryFixture
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

class ScheduleProviderLifecycleTest {
    @Test
    fun disposalJoinsDispatcherAndSessionWritesThenRejectsStaleHandles() = runTest {
        val dispatcherEntered = CompletableDeferred<Unit>()
        val writeEntered = CompletableDeferred<Unit>()
        val dispatcherCleaning = CompletableDeferred<Unit>()
        val writeCleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun block(entered: CompletableDeferred<Unit>, cleaning: CompletableDeferred<Unit>): Nothing {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                }
            }
        }
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun loadScheduledTasks(conversationId: Long?): List<ScheduledTask> =
                block(dispatcherEntered, dispatcherCleaning)
            override suspend fun upsertScheduledTask(conversationTitle: String, task: ScheduledTask) {
                block(writeEntered, writeCleaning)
            }
        }
        val coordinator = HistoryScheduledTaskCoordinator(repository, now = { 1_000L })
        val session = coordinator.sessionFor(1, "test")
        val dispatching = launch { coordinator.run { error("Must not dispatch") } }
        val writing = launch { session.create("task", "prompt", 1, null, null) }
        dispatcherEntered.await()
        writeEntered.await()
        val closing = launch { coordinator.close() }
        dispatcherCleaning.await()
        writeCleaning.await()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        closing.join()
        dispatching.join()
        writing.join()
        assertTrue(dispatching.isCancelled)
        assertTrue(writing.isCancelled)
        assertFailsWith<IllegalStateException> { coordinator.sessionFor(1, "test") }
        assertFailsWith<IllegalStateException> { coordinator.notifyChanged() }
        assertFailsWith<IllegalStateException> { coordinator.run { false } }
        assertFailsWith<IllegalStateException> { session.currentTimeEpochMillis() }
        assertFailsWith<IllegalStateException> { session.list() }
        assertFailsWith<IllegalStateException> { session.create("stale", "prompt", 1, null, null) }
    }
}
