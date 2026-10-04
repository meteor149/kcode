@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.plugin.goal

import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.ui.state.ConversationState
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ConversationGoalSession(
    private val conversation: ConversationState,
    private val repository: ConversationHistoryRepository,
    private val owner: PluginOperationOwner = PluginOperationOwner("Goal session"),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : GoalSession {
    private val mutex = Mutex()
    private suspend fun <T> owned(block: suspend () -> T): T = owner.run {
        mutex.withLock { block() }
    }

    override suspend fun continuationPrompt(): String? = owned {
        conversation.goal?.takeIf { it.status == ThreadGoalStatus.Active }?.let(::goalContinuationPrompt)
    }

    override suspend fun onCancelled() = transitionActiveGoal(ThreadGoalStatus.Paused)

    override suspend fun onFailed() = transitionActiveGoal(ThreadGoalStatus.Blocked)

    private suspend fun transitionActiveGoal(status: ThreadGoalStatus) = owned {
        val current = conversation.goal ?: return@owned
        if (current.status == ThreadGoalStatus.Active) {
            persist(current.copy(status = status, updatedAt = now()))
        }
        Unit
    }

    override suspend fun getGoal(): ThreadGoal? = owned { conversation.goal }

    override suspend fun createGoal(objective: String, tokenBudget: Long?): ThreadGoal = owned {
        validateObjective(objective)
        require(tokenBudget == null || tokenBudget > 0L) { "goal budgets must be positive when provided" }
        val current = conversation.goal
        require(current == null || current.status == ThreadGoalStatus.Complete) {
            "cannot create a new goal because this thread has an unfinished goal; complete the existing goal first"
        }
        persist(newGoal(objective.trim(), tokenBudget))
    }

    override suspend fun setGoalFromUser(objective: String): ThreadGoal = owned {
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

    override suspend fun editGoalFromUser(objective: String): ThreadGoal = owned {
        validateObjective(objective)
        val current = requireNotNull(conversation.goal) { "this conversation has no goal" }
        persist(current.copy(objective = objective.trim(), updatedAt = now()))
    }

    override suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal = owned {
        require(status in UserControlledStatuses) { "unsupported user-controlled goal status: $status" }
        val current = requireNotNull(conversation.goal) { "this conversation has no goal" }
        persist(current.copy(status = status, updatedAt = now()))
    }

    override suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal = owned {
        require(status == ThreadGoalStatus.Complete || status == ThreadGoalStatus.Blocked) {
            "update_goal can only mark the existing goal complete or blocked"
        }
        val current = requireNotNull(conversation.goal) { "cannot update goal because this thread has no goal" }
        persist(current.copy(status = status, updatedAt = now()))
    }

    override suspend fun clearGoal() = owned {
        repository.clearGoal(conversation.id)
        currentCoroutineContext().ensureActive()
        conversation.goal = null
    }

    override suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal? = owned {
        val current = conversation.goal ?: return@owned null
        if (current.status != ThreadGoalStatus.Active) return@owned current
        val used = (current.tokensUsed + tokens.coerceAtLeast(0L)).coerceAtLeast(current.tokensUsed)
        val budget = current.tokenBudget
        val status = if (budget != null && used >= budget) {
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
        repository.setGoal(conversation.id, conversation.title, goal)
        currentCoroutineContext().ensureActive()
        conversation.goal = goal
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
