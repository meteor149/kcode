package ai.meteor.kcode.plugin.scheduledispatch

import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Completion channel exposed only to the root agent executing a standalone scheduled task. */
class PersistedScheduledTaskCompletionSession(
    private val persistResult: suspend (String) -> Unit,
) : ScheduledTaskCompletionSession {
    private val completionMutex = Mutex()
    private var completedResult: String? = null

    override fun result(): String? = completedResult

    override suspend fun complete(result: String) = completionMutex.withLock {
        check(completedResult == null) { "scheduled task has already been completed" }
        val normalized = result.trim()
        require(normalized.isNotEmpty()) { "scheduled task result must not be empty" }
        persistResult(normalized)
        completedResult = normalized
    }
}
