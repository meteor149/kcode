package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun goalTools(session: GoalSession): ToolRegistry = ToolRegistry {
    tool(GetGoalTool(session))
    tool(CreateGoalTool(session))
    tool(UpdateGoalTool(session))
}

private class GetGoalTool(
    private val session: GoalSession,
) : SimpleTool<GetGoalTool.Args>(
    argsType = typeToken<Args>(),
    name = "get_goal",
    description = "Get the current goal for this thread, including status, budgets, token and elapsed-time usage, " +
        "and remaining token budget.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = goalResponse(session.getGoal())
}

private class CreateGoalTool(
    private val session: GoalSession,
) : SimpleTool<CreateGoalTool.Args>(
    argsType = typeToken<Args>(),
    name = "create_goal",
    description = """
        Create a goal only when explicitly requested by the user or system/developer instructions; do not infer goals from ordinary tasks.
        Set token_budget only when an explicit token budget is requested. Fails if an unfinished goal exists; use update_goal only for status.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        val objective: String,
        @SerialName("token_budget")
        val tokenBudget: Long? = null,
    )

    override suspend fun execute(args: Args): String = goalResponse(
        session.createGoal(args.objective, args.tokenBudget),
    )
}

private class UpdateGoalTool(
    private val session: GoalSession,
) : SimpleTool<UpdateGoalTool.Args>(
    argsType = typeToken<Args>(),
    name = "update_goal",
    description = """
        Update the existing goal.
        Use this tool only to mark the goal achieved or genuinely blocked.
        Set status to `complete` only when the objective has actually been achieved and no required work remains.
        Set status to `blocked` only when the same blocking condition has repeated for at least three consecutive goal turns, counting the original/user-triggered turn and any automatic continuations, and the agent cannot make meaningful progress without user input or an external-state change.
        If the user resumes a goal that was previously marked `blocked`, treat the resumed run as a fresh blocked audit. If the same blocking condition then repeats for at least three consecutive resumed goal turns, set status to `blocked` again.
        Once the blocked threshold is satisfied, do not keep reporting that you are still blocked while leaving the goal active; set status to `blocked`.
        Do not use `blocked` merely because the work is hard, slow, uncertain, incomplete, or would benefit from clarification.
        Do not mark a goal complete merely because its budget is nearly exhausted or because you are stopping work.
        You cannot use this tool to pause, resume, budget-limit, or usage-limit a goal; those status changes are controlled by the user or system.
        When marking a budgeted goal achieved with status `complete`, report the final token usage from the tool result to the user.
    """.trimIndent(),
) {
    @Serializable
    data class Args(val status: String)

    override suspend fun execute(args: Args): String {
        val status = when (args.status) {
            "complete" -> ThreadGoalStatus.Complete
            "blocked" -> ThreadGoalStatus.Blocked
            else -> error("update_goal status must be complete or blocked")
        }
        return goalResponse(session.updateGoalFromAgent(status), includeCompletionReport = status == ThreadGoalStatus.Complete)
    }
}

private fun goalResponse(goal: ThreadGoal?, includeCompletionReport: Boolean = false): String = buildJsonObject {
    if (goal == null) {
        put("goal", null)
        put("remainingTokens", null)
        put("completionBudgetReport", null)
        return@buildJsonObject
    }
    put("goal", buildJsonObject {
        put("objective", goal.objective)
        put("status", goal.status.name.toGoalWireValue())
        goal.tokenBudget?.let { put("tokenBudget", it) }
        put("tokensUsed", goal.tokensUsed)
        put("timeUsedSeconds", goal.timeUsedSeconds)
        put("createdAt", goal.createdAt)
        put("updatedAt", goal.updatedAt)
    })
    goal.remainingTokens?.let { put("remainingTokens", it) }
    if (includeCompletionReport && (goal.tokenBudget != null || goal.timeUsedSeconds > 0L)) {
        put(
            "completionBudgetReport",
            "Goal achieved. Report final usage from this tool result's structured goal fields.",
        )
    }
}.toString()

private fun String.toGoalWireValue(): String = fold(StringBuilder()) { result, character ->
    if (character.isUpperCase() && result.isNotEmpty()) result.append('_')
    result.append(character.lowercaseChar())
}.toString()
