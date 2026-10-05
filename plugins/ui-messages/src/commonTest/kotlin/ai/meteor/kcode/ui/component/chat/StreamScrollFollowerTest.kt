package ai.meteor.kcode.plugin.pages.chat.component

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Job

class StreamScrollFollowerTest {
    @Test
    fun contentGrowthDoesNotEnableFollowing() {
        val follower = StreamScrollFollower()
        follower.stopFollowing()

        follower.onScrollStateChanged(isScrollInProgress = false, isAtBottom = true)

        assertFalse(follower.followLatest)
    }

    @Test
    fun userScrollPausesFollowingUntilItStopsAtBottom() {
        val follower = StreamScrollFollower()

        follower.onScrollStateChanged(isScrollInProgress = true, isAtBottom = false)
        assertFalse(follower.followLatest)

        follower.onScrollStateChanged(isScrollInProgress = false, isAtBottom = false)
        assertFalse(follower.followLatest)

        follower.onScrollStateChanged(isScrollInProgress = true, isAtBottom = false)
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
