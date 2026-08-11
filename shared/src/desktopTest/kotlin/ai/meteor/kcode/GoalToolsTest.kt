package ai.meteor.kcode

import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class GoalToolsTest {
    @Test
    fun exposesTheCodexGoalToolSurface() {
        val registry = goalTools(FakeGoalSession())

        assertEquals(setOf("get_goal", "create_goal", "update_goal"), registry.tools.map { it.name }.toSet())
        assertEquals(
            setOf("objective"),
            requireNotNull(registry.getToolOrNull("create_goal")).descriptor.requiredParameters.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("token_budget"),
            requireNotNull(registry.getToolOrNull("create_goal")).descriptor.optionalParameters.map { it.name }.toSet(),
        )
    }

    @Test
    fun activeGoalSchedulesTheSameContinuationAfterAgentCoordinationIsIdle() = runTest {
        val coordinator = MultiAgentCoordinator(
            scope = backgroundScope,
            rootContext = "context",
            runAgent = { "done" },
        )
        val active = FakeGoalSession(
            ThreadGoal("id", "Complete the work", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1),
        )

        assertContains(requireNotNull(nextRootContinuation(coordinator, active)), "Complete the work")
        active.goal = active.goal?.copy(status = ThreadGoalStatus.Complete)
        assertNull(nextRootContinuation(coordinator, active))
    }
}

private class FakeGoalSession(var goal: ThreadGoal? = null) : GoalSession {
    override suspend fun getGoal(): ThreadGoal? = goal
    override suspend fun createGoal(objective: String, tokenBudget: Long?): ThreadGoal = error("unused")
    override suspend fun setGoalFromUser(objective: String): ThreadGoal = error("unused")
    override suspend fun editGoalFromUser(objective: String): ThreadGoal = error("unused")
    override suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal = error("unused")
    override suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal = error("unused")
    override suspend fun clearGoal() = Unit
    override suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal? = null
}
