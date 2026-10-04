package ai.meteor.kcode.plugin.overlay

import kotlinx.coroutines.delay

internal suspend fun waitForConversationOverlayPermission(
    hasPermission: () -> Boolean,
    wait: suspend (Long) -> Unit = { delay(it) },
    checkIntervalMillis: Long = 5_000L,
    maxChecks: Int = 5,
): Boolean {
    require(checkIntervalMillis > 0) { "checkIntervalMillis must be positive" }
    require(maxChecks > 0) { "maxChecks must be positive" }
    repeat(maxChecks) {
        wait(checkIntervalMillis)
        if (hasPermission()) return true
    }
    return false
}
