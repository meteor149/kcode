package ai.meteor.kcode.chat

/** Completion channel exposed only to the root agent executing a standalone scheduled task. */
interface ScheduledTaskCompletionSession {
    fun result(): String?

    suspend fun complete(result: String)
}
