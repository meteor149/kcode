@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.chat

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.ui.state.ConversationState
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GoalCommand {
    data object Show : GoalCommand
    data class Set(val objective: String) : GoalCommand
    data class Edit(val objective: String) : GoalCommand
    data object Pause : GoalCommand
    data object Resume : GoalCommand
    data object Clear : GoalCommand
}

fun parseGoalCommand(input: String): GoalCommand? {
    val value = input.trim()
    if (value == "/goal") return GoalCommand.Show
    if (!value.startsWith("/goal ")) return null
    val argument = value.removePrefix("/goal ").trim()
    return when {
        argument == "pause" -> GoalCommand.Pause
        argument == "resume" -> GoalCommand.Resume
        argument == "clear" -> GoalCommand.Clear
        argument == "edit" -> GoalCommand.Edit("")
        argument.startsWith("edit ") -> GoalCommand.Edit(argument.removePrefix("edit ").trim())
        else -> GoalCommand.Set(argument)
    }
}

interface GoalSession {
    suspend fun getGoal(): ThreadGoal?
    suspend fun createGoal(objective: String, tokenBudget: Long? = null): ThreadGoal
    suspend fun setGoalFromUser(objective: String): ThreadGoal
    suspend fun editGoalFromUser(objective: String): ThreadGoal
    suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal
    suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal
    suspend fun clearGoal()
    suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal?
}

internal class ConversationGoalSession(
    private val conversation: ConversationState,
    private val repository: ConversationHistoryRepository,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : GoalSession {
    private val mutex = Mutex()

    override suspend fun getGoal(): ThreadGoal? = mutex.withLock { conversation.goal }

    override suspend fun createGoal(objective: String, tokenBudget: Long?): ThreadGoal = mutex.withLock {
        validateObjective(objective)
        require(tokenBudget == null || tokenBudget > 0L) { "goal budgets must be positive when provided" }
        val current = conversation.goal
        require(current == null || current.status == ThreadGoalStatus.Complete) {
            "cannot create a new goal because this thread has an unfinished goal; complete the existing goal first"
        }
        persist(newGoal(objective.trim(), tokenBudget))
    }

    override suspend fun setGoalFromUser(objective: String): ThreadGoal = mutex.withLock {
        validateObjective(objective)
        val current = conversation.goal
        persist(
            current?.copy(
                objective = objective.trim(),
                status = ThreadGoalStatus.Active,
                updatedAt = now(),
            ) ?: newGoal(objective.trim(), tokenBudget = null),
        )
    }

    override suspend fun editGoalFromUser(objective: String): ThreadGoal = mutex.withLock {
        validateObjective(objective)
        val current = requireNotNull(conversation.goal) { "this conversation has no goal" }
        persist(current.copy(objective = objective.trim(), updatedAt = now()))
    }

    override suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal = mutex.withLock {
        require(status in UserControlledStatuses) { "unsupported user-controlled goal status: $status" }
        val current = requireNotNull(conversation.goal) { "this conversation has no goal" }
        persist(current.copy(status = status, updatedAt = now()))
    }

    override suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal = mutex.withLock {
        require(status == ThreadGoalStatus.Complete || status == ThreadGoalStatus.Blocked) {
            "update_goal can only mark the existing goal complete or blocked"
        }
        val current = requireNotNull(conversation.goal) { "cannot update goal because this thread has no goal" }
        persist(current.copy(status = status, updatedAt = now()))
    }

    override suspend fun clearGoal() = mutex.withLock {
        conversation.goal = null
        repository.clearGoal(conversation.id)
    }

    override suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal? = mutex.withLock {
        val current = conversation.goal ?: return@withLock null
        if (current.status != ThreadGoalStatus.Active) return@withLock current
        val used = (current.tokensUsed + tokens.coerceAtLeast(0L)).coerceAtLeast(current.tokensUsed)
        val status = if (current.tokenBudget != null && used >= current.tokenBudget) {
            ThreadGoalStatus.BudgetLimited
        } else {
            current.status
        }
        persist(
            current.copy(
                status = status,
                tokensUsed = used,
                timeUsedSeconds = current.timeUsedSeconds + elapsedSeconds.coerceAtLeast(0L),
                updatedAt = now(),
            ),
        )
    }

    private fun newGoal(objective: String, tokenBudget: Long?): ThreadGoal {
        val timestamp = now()
        return ThreadGoal(
            goalId = "${conversation.id}-$timestamp",
            objective = objective,
            status = ThreadGoalStatus.Active,
            tokenBudget = tokenBudget,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
    }

    private suspend fun persist(goal: ThreadGoal): ThreadGoal {
        conversation.goal = goal
        repository.setGoal(conversation.id, conversation.title, goal)
        return goal
    }

    private fun validateObjective(objective: String) {
        require(objective.isNotBlank()) { "goal objective must not be empty" }
    }

    private companion object {
        val UserControlledStatuses = setOf(
            ThreadGoalStatus.Active,
            ThreadGoalStatus.Paused,
            ThreadGoalStatus.Blocked,
            ThreadGoalStatus.UsageLimited,
            ThreadGoalStatus.BudgetLimited,
            ThreadGoalStatus.Complete,
        )
    }
}
