package ai.meteor.kcode.plugin.scheduledispatch

fun scheduledTaskExecutionPrompt(prompt: String): String = """
    $prompt

    This is a standalone scheduled-task run. Complete all required work first. When the task is
    finished, call `complete_scheduled_task` exactly once with the concise, user-facing result that
    should appear in the notification and floating conversation. The result must be understandable
    without the execution log. Treat that tool call as the final completion signal.
""".trimIndent()
