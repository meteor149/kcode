package ai.meteor.kcode.plugin.goal

import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class HistoryGoalSessions(private val repository: ConversationHistoryRepository) : GoalSessionFactory {
    private val owner = PluginOperationOwner("Goal provider")
    private val sessions = MutableStateFlow<Map<ConversationState, ConversationGoalSession>>(emptyMap())

    override fun create(conversation: ConversationState): GoalSession {
        owner.requireOpen()
        sessions.update { current ->
            if (conversation in current) current else current +
                (conversation to ConversationGoalSession(conversation, repository, owner))
        }
        owner.requireOpen()
        return sessions.value.getValue(conversation)
    }

    suspend fun close() {
        try {
            owner.close()
        } finally {
            sessions.value = emptyMap()
        }
    }
}
