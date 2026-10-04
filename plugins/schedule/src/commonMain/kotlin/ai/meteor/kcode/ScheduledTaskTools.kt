package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.history.ScheduledTask
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun scheduledTaskTools(session: ScheduledTaskSession): ToolRegistry = ToolRegistry {
    tool(ScheduleTaskTool(session))
}

private class ScheduleTaskTool(
    private val session: ScheduledTaskSession,
) : SimpleTool<ScheduleTaskTool.Args>(
    argsType = typeToken<Args>(),
    name = "schedule_task",
    description = """
        Create and manage scheduled prompts for the current conversation.
        Use this tool only when the user explicitly asks to schedule, remind, monitor, pause, resume, list, or cancel a task.
        Supported actions are `create`, `create_recurring`, `list`, `pause`, `resume`, and `cancel`.
        For `create`, provide name, a durable self-contained prompt, and exactly one of positive delay_seconds relative to now or future run_at_epoch_millis.
        For `create_recurring`, provide the same fields plus repeat_interval_seconds. The first run uses delay_seconds or run_at_epoch_millis, then it repeats at that interval. Recurring intervals must be at least 60 seconds.
        `create` also accepts repeat_interval_seconds for backwards compatibility, but prefer `create_recurring` whenever the user asks for periodic, recurring, repeated, daily, weekly, monitoring, or polling execution.
        For pause, resume, or cancel, provide task_id from a previous result or list call.
        Every run creates a separate standalone conversation. It never appends the result to the current conversation.
        Tasks run while the app process is available. Overdue tasks are restored when the app starts again.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        val action: String,
        val name: String? = null,
        val prompt: String? = null,
        @SerialName("delay_seconds")
        val delaySeconds: Long? = null,
        @SerialName("run_at_epoch_millis")
        val runAtEpochMillis: Long? = null,
        @SerialName("repeat_interval_seconds")
        val repeatIntervalSeconds: Long? = null,
        @SerialName("task_id")
        val taskId: String? = null,
    )

    override suspend fun execute(args: Args): String = when (args.action) {
        "create", "create_recurring" -> taskResponse(
            session.create(
                name = requireNotNull(args.name) { "name is required for create" },
                prompt = requireNotNull(args.prompt) { "prompt is required for create" },
                delaySeconds = args.delaySeconds,
                runAtEpochMillis = args.runAtEpochMillis,
                repeatIntervalSeconds = args.repeatIntervalSeconds.also { interval ->
                    if (args.action == "create_recurring") {
                        requireNotNull(interval) {
                            "repeat_interval_seconds is required for create_recurring"
                        }
                    }
                },
            ),
            session.currentTimeEpochMillis(),
        )
        "list" -> taskListResponse(session.list(), session.currentTimeEpochMillis())
        "pause" -> taskResponse(session.pause(args.requiredTaskId()), session.currentTimeEpochMillis())
        "resume" -> taskResponse(session.resume(args.requiredTaskId()), session.currentTimeEpochMillis())
        "cancel" -> {
            val taskId = args.requiredTaskId()
            session.cancel(taskId)
            buildJsonObject {
                put("cancelled", true)
                put("taskId", taskId)
                put("currentTimeEpochMillis", session.currentTimeEpochMillis())
            }.toString()
        }
        else -> error("action must be create, create_recurring, list, pause, resume, or cancel")
    }

    private fun Args.requiredTaskId(): String = requireNotNull(taskId) { "task_id is required for $action" }
}

private fun taskListResponse(tasks: List<ScheduledTask>, currentTime: Long): String = buildJsonObject {
    put("currentTimeEpochMillis", currentTime)
    put("tasks", buildJsonArray { tasks.forEach { add(taskJson(it)) } })
}.toString()

private fun taskResponse(task: ScheduledTask, currentTime: Long): String = buildJsonObject {
    put("currentTimeEpochMillis", currentTime)
    put("task", taskJson(task))
}.toString()

private fun taskJson(task: ScheduledTask) = buildJsonObject {
    put("taskId", task.taskId)
    put("name", task.name)
    put("prompt", task.prompt)
    put("status", task.status.name.lowercase())
    put("scheduleType", if (task.repeatIntervalMillis == null) "one_shot" else "recurring")
    put("nextRunAtEpochMillis", task.nextRunAt)
    task.repeatIntervalMillis?.let { put("repeatIntervalSeconds", it / 1_000L) }
    task.lastRunAt?.let { put("lastRunAtEpochMillis", it) }
    put("createdAtEpochMillis", task.createdAt)
    put("updatedAtEpochMillis", task.updatedAt)
}
