package ai.meteor.kcode

import ai.meteor.kcode.model.ChatMessage
import kotlinx.coroutines.delay

interface AgentConversationOverlayController {
    suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn

    fun setHostForeground(isForeground: Boolean)

    fun close() = Unit
}

interface AgentConversationOverlayTurn {
    suspend fun update(messages: List<ChatMessage>)

    suspend fun finish()
}

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
