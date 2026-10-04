@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.chat

import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.ui.state.ConversationState

interface GoalSession : ConversationResponseLifecycle {
    /** Null when this provider has no further root response to request. */
    suspend fun continuationPrompt(): String? = null
    suspend fun getGoal(): ThreadGoal?
    suspend fun createGoal(objective: String, tokenBudget: Long? = null): ThreadGoal
    suspend fun setGoalFromUser(objective: String): ThreadGoal
    suspend fun editGoalFromUser(objective: String): ThreadGoal
    suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal
    suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal
    suspend fun clearGoal()
    suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal?
}

/** UI and model consumers use the provider's session, never construct repository-backed state. */
fun interface GoalSessionFactory {
    fun create(conversation: ConversationState): GoalSession?
}

object UnavailableGoalSessions : GoalSessionFactory {
    override fun create(conversation: ConversationState): GoalSession? = null
}
