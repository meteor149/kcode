package ai.meteor.kcode.chat

/** Completion channel exposed only to the root agent executing a standalone scheduled task. */
class ScheduledTaskCompletionSession(
    private val persistResult: suspend (String) -> Unit,
) {
    private var completedResult: String? = null

    fun result(): String? = completedResult

    suspend fun complete(result: String) {
        check(completedResult == null) { "scheduled task has already been completed" }
        val normalized = result.trim()
        require(normalized.isNotEmpty()) { "scheduled task result must not be empty" }
        persistResult(normalized)
        completedResult = normalized
    }
}

internal fun scheduledTaskExecutionPrompt(prompt: String): String = """
    $prompt

    This is a standalone scheduled-task run. Complete all required work first. When the task is
    finished, call `complete_scheduled_task` exactly once with the concise, user-facing result that
    should appear in the notification and floating conversation. The result must be understandable
    without the execution log. Treat that tool call as the final completion signal.
""".trimIndent()
