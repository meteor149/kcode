package ai.meteor.kcode

import ai.meteor.kcode.plugin.goal.goalContinuationPrompt
import ai.meteor.kcode.plugin.GoalContinuationPlugin
import ai.meteor.kcode.plugin.api.KcodeContinuations
import org.cordis.Context
import org.cordis.plugin
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    fun activeGoalContinuationIsARevocablePolicyContribution() = runTest {
        val ctx = Context()
        val service = ctx.plugin(plugin<Unit>(name = "test-continuation-registry") { context, _ ->
            KcodeContinuations(context)
        }, Unit).await()
        val contribution = ctx.plugin(GoalContinuationPlugin, Unit).await()
        val active = FakeGoalSession(
            ThreadGoal("id", "Complete the work", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1),
        )
        val continuation = AgentContinuationContext(
            subagentContinuation = { error("The goal policy must not drive subagent coordination") },
            goalContinuation = active::continuationPrompt,
        )
        try {
            val registry = ctx.require(KcodeContinuations.Key)
            assertContains(requireNotNull(registry.next(continuation)), "Complete the work")
            active.goal = active.goal?.copy(status = ThreadGoalStatus.Complete)
            assertNull(registry.next(continuation))
            active.goal = active.goal?.copy(status = ThreadGoalStatus.Active)
            contribution.dispose()
            assertNull(registry.next(continuation))
        } finally {
            contribution.dispose()
            service.dispose()
        }
    }

}

private class FakeGoalSession(var goal: ThreadGoal? = null) : GoalSession {
    override suspend fun continuationPrompt(): String? = goal?.takeIf { it.status == ThreadGoalStatus.Active }?.let(::goalContinuationPrompt)
    override suspend fun getGoal(): ThreadGoal? = goal
    override suspend fun createGoal(objective: String, tokenBudget: Long?): ThreadGoal = error("unused")
    override suspend fun setGoalFromUser(objective: String): ThreadGoal = error("unused")
    override suspend fun editGoalFromUser(objective: String): ThreadGoal = error("unused")
    override suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal = error("unused")
    override suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal = error("unused")
    override suspend fun clearGoal() = Unit
    override suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal? = null
}
