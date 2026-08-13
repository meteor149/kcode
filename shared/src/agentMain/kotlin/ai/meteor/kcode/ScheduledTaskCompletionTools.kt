package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun scheduledTaskCompletionTools(session: ScheduledTaskCompletionSession): ToolRegistry = ToolRegistry {
    tool(CompleteScheduledTaskTool(session))
}

private class CompleteScheduledTaskTool(
    private val session: ScheduledTaskCompletionSession,
) : SimpleTool<CompleteScheduledTaskTool.Args>(
    argsType = typeToken<Args>(),
    name = "complete_scheduled_task",
    description = """
        Complete the current standalone scheduled-task run and select its user-facing result.
        Call this exactly once, only after all required work has finished. `result` is displayed
        verbatim in the completion notification and as the primary content of the floating result
        card, so it must be concise, self-contained, and omit internal execution narration.
    """.trimIndent(),
) {
    @Serializable
    data class Args(val result: String)

    override suspend fun execute(args: Args): String {
        session.complete(args.result)
        return buildJsonObject {
            put("completed", true)
            put("result", args.result.trim())
        }.toString()
    }
}
