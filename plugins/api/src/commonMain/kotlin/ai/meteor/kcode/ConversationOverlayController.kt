package ai.meteor.kcode

import ai.meteor.kcode.model.ChatMessage

interface AgentConversationOverlayController {
    suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn

    suspend fun setHostForeground(isForeground: Boolean)

    suspend fun close() = Unit
}

interface AgentConversationOverlayTurn {
    suspend fun update(messages: List<ChatMessage>)

    suspend fun finish()
}
