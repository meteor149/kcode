package ai.meteor.kcode.ui.pages.chat

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamScrollFollowerTest {
    @Test
    fun contentGrowthAwayFromBottomKeepsCurrentFollowMode() {
        val follower = StreamScrollFollower()

        follower.onScrollStateChanged(isScrollInProgress = false, isAtBottom = false)

        assertTrue(follower.followLatest)
    }

    @Test
    fun userScrollPausesFollowingUntilItStopsAtBottom() {
        val follower = StreamScrollFollower()

        follower.onScrollStateChanged(isScrollInProgress = true, isAtBottom = false)
        assertFalse(follower.followLatest)

        follower.onScrollStateChanged(isScrollInProgress = false, isAtBottom = false)
        assertFalse(follower.followLatest)

        follower.onScrollStateChanged(isScrollInProgress = false, isAtBottom = true)
        assertTrue(follower.followLatest)
    }

    @Test
    fun activeAutomaticScrollDoesNotPauseFollowing() {
        val follower = StreamScrollFollower().apply { job = Job() }

        follower.onScrollStateChanged(isScrollInProgress = true, isAtBottom = false)

        assertTrue(follower.followLatest)
        follower.job?.cancel()
    }

    @Test
    fun programmaticScrollRequiresFollowingToBeEnabled() {
        val follower = StreamScrollFollower()

        assertTrue(follower.shouldScrollProgrammatically())

        follower.stopFollowing()

        assertFalse(follower.shouldScrollProgrammatically())
    }
}
