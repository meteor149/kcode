package ai.meteor.kcode.chat

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.ScheduledTaskStatus
import ai.meteor.kcode.history.TransientConversationHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScheduledTaskRuntimeTest {
    @Test
    fun completionSessionPersistsExactlyOneLlmSelectedResult() = runTest {
        val persisted = mutableListOf<String>()
        val session = ScheduledTaskCompletionSession { persisted += it }

        session.complete("  Build passed  ")

        assertEquals("Build passed", session.result())
        assertEquals(listOf("Build passed"), persisted)
        assertFailsWith<IllegalStateException> { session.complete("Second result") }
    }

    @Test
    fun createsAndManagesConversationScopedTask() = runTest {
        var now = 1_000L
        val repository = RecordingScheduledTaskRepository()
        val session = ConversationScheduledTaskSession(
            conversationId = 7L,
            conversationTitle = "Release checks",
            repository = repository,
            now = { now },
        )

        val created = session.create(
            name = "Check build",
            prompt = "Check whether the build completed.",
            delaySeconds = 2L,
            runAtEpochMillis = null,
            repeatIntervalSeconds = 60L,
        )

        assertEquals(7L, created.conversationId)
        assertEquals(3_000L, created.nextRunAt)
        assertEquals(60_000L, created.repeatIntervalMillis)
        assertEquals(ScheduledTaskStatus.Active, created.status)
        assertEquals(listOf(created), session.list())

        val paused = session.pause(created.taskId)
        assertEquals(ScheduledTaskStatus.Paused, paused.status)

        now = 5_000L
        val resumed = session.resume(created.taskId)
        assertEquals(ScheduledTaskStatus.Active, resumed.status)
        assertEquals(6_000L, resumed.nextRunAt)

        session.cancel(created.taskId)
        assertEquals(emptyList(), session.list())
    }

    @Test
    fun rejectsUnsafeRepeatFrequency() = runTest {
        val session = ConversationScheduledTaskSession(
            conversationId = 1L,
            conversationTitle = "Chat",
            repository = RecordingScheduledTaskRepository(),
            now = { 10L },
        )

        assertFailsWith<IllegalArgumentException> {
            session.create("Poll", "Poll now", delaySeconds = 1L, repeatIntervalSeconds = 59L)
        }
    }

    @Test
    fun createsTaskAtAbsoluteTime() = runTest {
        val session = ConversationScheduledTaskSession(
            conversationId = 1L,
            conversationTitle = "Chat",
            repository = RecordingScheduledTaskRepository(),
            now = { 1_000L },
        )

        val task = session.create(
            name = "Reminder",
            prompt = "Send the reminder.",
            runAtEpochMillis = 5_000L,
        )

        assertEquals(5_000L, task.nextRunAt)
    }

    @Test
    fun advancesRecurringTaskPastMissedRuns() {
        val recurring = task(nextRunAt = 1_000L, repeatIntervalMillis = 1_000L)

        val advanced = recurring.afterDispatch(timestamp = 3_500L)

        assertEquals(ScheduledTaskStatus.Active, advanced.status)
        assertEquals(4_000L, advanced.nextRunAt)
        assertEquals(3_500L, advanced.lastRunAt)
    }

    @Test
    fun completesOneShotTaskAfterDispatch() {
        val completed = task(nextRunAt = 1_000L).afterDispatch(timestamp = 2_000L)

        assertEquals(ScheduledTaskStatus.Completed, completed.status)
        assertEquals(2_000L, completed.lastRunAt)
        assertNull(completed.repeatIntervalMillis)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coordinatorDispatchesDueTaskAndMarksItCompleted() = runTest {
        val repository = RecordingScheduledTaskRepository()
        val due = task(nextRunAt = 1_000L)
        repository.upsertScheduledTask("Chat", due)
        val dispatched = mutableListOf<ScheduledTask>()
        val coordinator = ScheduledTaskCoordinator(repository, now = { 1_000L })

        val job = launch {
            coordinator.run { task ->
                dispatched += task
                true
            }
        }
        runCurrent()
        job.cancel()

        assertEquals(listOf(due), dispatched)
        assertEquals(ScheduledTaskStatus.Completed, repository.loadScheduledTasks().single().status)
    }

    private fun task(nextRunAt: Long, repeatIntervalMillis: Long? = null) = ScheduledTask(
        taskId = "task-1",
        conversationId = 1L,
        name = "Task",
        prompt = "Do the task",
        status = ScheduledTaskStatus.Active,
        nextRunAt = nextRunAt,
        repeatIntervalMillis = repeatIntervalMillis,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private class RecordingScheduledTaskRepository :
    ConversationHistoryRepository by TransientConversationHistoryRepository {
    private val tasks = linkedMapOf<String, ScheduledTask>()

    override suspend fun loadScheduledTasks(conversationId: Long?): List<ScheduledTask> = tasks.values
        .filter { conversationId == null || it.conversationId == conversationId }
        .sortedBy(ScheduledTask::nextRunAt)

    override suspend fun upsertScheduledTask(title: String, task: ScheduledTask) {
        tasks[task.taskId] = task
    }

    override suspend fun updateScheduledTask(task: ScheduledTask) {
        if (task.taskId in tasks) tasks[task.taskId] = task
    }

    override suspend fun deleteScheduledTask(conversationId: Long, taskId: String) {
        tasks[taskId]?.takeIf { it.conversationId == conversationId }?.let { tasks.remove(taskId) }
    }
}
