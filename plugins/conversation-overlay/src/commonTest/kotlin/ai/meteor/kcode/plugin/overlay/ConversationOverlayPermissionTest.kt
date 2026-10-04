package ai.meteor.kcode.plugin.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ConversationOverlayPermissionTest {
    @Test
    fun permissionWaitChecksEveryIntervalAndStopsAfterFifthFailure() = runBlocking {
        var checks = 0
        val waits = mutableListOf<Long>()

        val granted = waitForConversationOverlayPermission(
            hasPermission = {
                checks += 1
                false
            },
            wait = waits::add,
        )

        assertEquals(false, granted)
        assertEquals(5, checks)
        assertEquals(List(5) { 5_000L }, waits)
    }

    @Test
    fun permissionWaitReturnsAtFirstSuccessfulCheck() = runBlocking {
        var checks = 0
        val waits = mutableListOf<Long>()

        val granted = waitForConversationOverlayPermission(
            hasPermission = {
                checks += 1
                checks == 3
            },
            wait = waits::add,
        )

        assertEquals(true, granted)
        assertEquals(3, checks)
        assertEquals(List(3) { 5_000L }, waits)
    }
}
