package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalBannerVisibilityTest {
    @Test
    fun completedGoalRemainsVisibleForTheRestOfItsThreeSecondWindow() {
        val goal = goal(status = ThreadGoalStatus.Complete, updatedAt = 1_000L)

        assertEquals(2_250L, goal.completedBannerRemainingMillis(nowMillis = 1_750L))
    }

    @Test
    fun completedGoalIsHiddenOnceItsVisibilityWindowExpires() {
        val goal = goal(status = ThreadGoalStatus.Complete, updatedAt = 1_000L)

        assertEquals(0L, goal.completedBannerRemainingMillis(nowMillis = 4_000L))
        assertEquals(0L, goal.completedBannerRemainingMillis(nowMillis = 10_000L))
    }

    @Test
    fun aNewActiveGoalHasNoPendingDismissal() {
        val goal = goal(status = ThreadGoalStatus.Active, updatedAt = 2_000L)

        assertEquals(null, goal.completedBannerRemainingMillis(nowMillis = 10_000L))
    }

    private fun goal(status: ThreadGoalStatus, updatedAt: Long) = ThreadGoal(
        goalId = "goal-id",
        objective = "Finish the task",
        status = status,
        createdAt = 1_000L,
        updatedAt = updatedAt,
    )
}
