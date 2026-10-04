@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.chat

import ai.meteor.kcode.history.ScheduledTask

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

/** Replaceable scheduling data plane; the host owns its execution scope and system notification bridge. */
interface ScheduledTaskCoordinator {
    fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession?
    suspend fun run(onDue: suspend (ScheduledTask) -> Boolean)
    fun notifyChanged()
}

object UnavailableScheduledTasks : ScheduledTaskCoordinator {
    override fun sessionFor(conversationId: Long, title: String): ScheduledTaskSession? = null
    override suspend fun run(onDue: suspend (ScheduledTask) -> Boolean) = kotlinx.coroutines.awaitCancellation()
    override fun notifyChanged() = Unit
}
