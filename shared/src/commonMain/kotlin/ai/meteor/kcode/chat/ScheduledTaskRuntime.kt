@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.chat

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.ScheduledTaskStatus
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

interface ScheduledTaskSession {
    fun currentTimeEpochMillis(): Long
    suspend fun list(): List<ScheduledTask>

    suspend fun create(
        name: String,
        prompt: String,
        delaySeconds: Long? = null,
        runAtEpochMillis: Long? = null,
        repeatIntervalSeconds: Long? = null,
    ): ScheduledTask

    suspend fun pause(taskId: String): ScheduledTask
    suspend fun resume(taskId: String): ScheduledTask
    suspend fun cancel(taskId: String)
}

class ScheduledTaskCoordinator(
    private val repository: ConversationHistoryRepository,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val changes = Channel<Unit>(capacity = Channel.CONFLATED)

    fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession =
        ConversationScheduledTaskSession(
            conversationId = conversationId,
            conversationTitle = title,
            repository = repository,
            now = now,
            onChanged = ::notifyChanged,
        )

    /**
     * Keeps dispatching tasks while its caller's scope is alive. Returning false from [onDue]
     * leaves a task pending and retries it shortly, which avoids overlapping turns in one chat.
     */
    suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) {
        while (currentCoroutineContext().isActive) {
            try {
                dispatchNext(onDue)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                delay(RetryDelayMillis)
            }
        }
    }

    fun notifyChanged() {
        changes.trySend(Unit)
    }

    private suspend fun dispatchNext(onDue: suspend (ScheduledTask) -> Boolean) {
        val active = repository.loadScheduledTasks()
            .filter { it.status == ScheduledTaskStatus.Active }
            .sortedBy(ScheduledTask::nextRunAt)
        val next = active.firstOrNull()
        if (next == null) {
            changes.receive()
            return
        }

        val waitMillis = (next.nextRunAt - now()).coerceIn(0L, MaximumWaitMillis)
        if (waitMillis > 0L) {
            withTimeoutOrNull(waitMillis) { changes.receive() }
            return
        }

        val dueTasks = repository.loadScheduledTasks()
            .filter { it.status == ScheduledTaskStatus.Active && it.nextRunAt <= now() }
            .sortedBy(ScheduledTask::nextRunAt)
        var acceptedAny = false
        for (task in dueTasks) {
            if (!onDue(task)) continue
            acceptedAny = true
            repository.updateScheduledTask(task.afterDispatch(now()))
        }
        if (dueTasks.isNotEmpty() && !acceptedAny) delay(RetryDelayMillis)
    }

    private companion object {
        const val RetryDelayMillis = 5_000L
        const val MaximumWaitMillis = 60_000L
    }
}

internal class ConversationScheduledTaskSession(
    private val conversationId: Long,
    private val conversationTitle: String,
    private val repository: ConversationHistoryRepository,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onChanged: () -> Unit = {},
) : ScheduledTaskSession {
    override fun currentTimeEpochMillis(): Long = now()

    override suspend fun list(): List<ScheduledTask> = repository.loadScheduledTasks(conversationId)

    override suspend fun create(
        name: String,
        prompt: String,
        delaySeconds: Long?,
        runAtEpochMillis: Long?,
        repeatIntervalSeconds: Long?,
    ): ScheduledTask {
        require(name.isNotBlank()) { "scheduled task name must not be empty" }
        require(prompt.isNotBlank()) { "scheduled task prompt must not be empty" }
        require((delaySeconds == null) != (runAtEpochMillis == null)) {
            "provide exactly one of delay_seconds or run_at_epoch_millis"
        }
        require(delaySeconds == null || delaySeconds > 0L) { "delay_seconds must be positive" }
        require(repeatIntervalSeconds == null || repeatIntervalSeconds >= MinimumRepeatSeconds) {
            "repeat_interval_seconds must be at least $MinimumRepeatSeconds"
        }
        val timestamp = now()
        val nextRunAt = runAtEpochMillis ?: requireNotNull(delaySeconds).safeMillis().let { delayMillis ->
            require(timestamp <= Long.MAX_VALUE - delayMillis) { "scheduled task delay is too large" }
            timestamp + delayMillis
        }
        require(nextRunAt > timestamp) { "run_at_epoch_millis must be in the future" }
        val task = ScheduledTask(
            taskId = "$conversationId-$timestamp-${Random.nextInt().toUInt().toString(16)}",
            conversationId = conversationId,
            name = name.trim(),
            prompt = prompt.trim(),
            status = ScheduledTaskStatus.Active,
            nextRunAt = nextRunAt,
            repeatIntervalMillis = repeatIntervalSeconds?.safeMillis(),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        repository.upsertScheduledTask(conversationTitle, task)
        onChanged()
        return task
    }

    override suspend fun pause(taskId: String): ScheduledTask = update(taskId) { task, timestamp ->
        task.copy(status = ScheduledTaskStatus.Paused, updatedAt = timestamp)
    }

    override suspend fun resume(taskId: String): ScheduledTask = update(taskId) { task, timestamp ->
        require(task.status != ScheduledTaskStatus.Completed) { "completed one-shot tasks cannot be resumed" }
        task.copy(
            status = ScheduledTaskStatus.Active,
            nextRunAt = maxOf(task.nextRunAt, timestamp + 1_000L),
            updatedAt = timestamp,
        )
    }

    override suspend fun cancel(taskId: String) {
        requireTask(taskId)
        repository.deleteScheduledTask(conversationId, taskId)
        onChanged()
    }

    private suspend fun update(
        taskId: String,
        transform: (ScheduledTask, Long) -> ScheduledTask,
    ): ScheduledTask {
        val updated = transform(requireTask(taskId), now())
        repository.upsertScheduledTask(conversationTitle, updated)
        onChanged()
        return updated
    }

    private suspend fun requireTask(taskId: String): ScheduledTask =
        list().firstOrNull { it.taskId == taskId }
            ?: error("scheduled task does not exist in this conversation: $taskId")

    private fun Long.safeMillis(): Long {
        require(this <= Long.MAX_VALUE / 1_000L) { "scheduled task delay is too large" }
        return this * 1_000L
    }

    private companion object {
        const val MinimumRepeatSeconds = 60L
    }
}

internal fun ScheduledTask.afterDispatch(timestamp: Long): ScheduledTask {
    val interval = repeatIntervalMillis
    if (interval == null) {
        return copy(
            status = ScheduledTaskStatus.Completed,
            updatedAt = timestamp,
            lastRunAt = timestamp,
        )
    }
    val intervalsToSkip = ((timestamp - nextRunAt) / interval) + 1L
    val followingRun = if (intervalsToSkip > (Long.MAX_VALUE - nextRunAt) / interval) {
        Long.MAX_VALUE
    } else {
        nextRunAt + intervalsToSkip * interval
    }
    return copy(
        nextRunAt = followingRun,
        updatedAt = timestamp,
        lastRunAt = timestamp,
    )
}
