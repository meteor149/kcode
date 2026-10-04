package ai.meteor.kcode.chat

import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CoroutineScope

/** A host projection onto the session data plane, not a repository implementation. */
interface ConversationSession {
    val conversations: List<ConversationState>
    val floatingConversations: List<ConversationState>
    val activeId: Long?
    val isLoaded: Boolean
    val failureMessage: String?
    suspend fun load()
    fun selectConversation(id: Long)
    fun startNewConversation()
    fun ensureConversation(prompt: String): ConversationState
    suspend fun createPendingStandaloneConversation(title: String): ConversationState
    suspend fun revealStandaloneConversation(id: Long)
    suspend fun setPendingStandaloneResult(id: Long, result: String)
    suspend fun appendPendingStandaloneResultMessage(id: Long)
    suspend fun discardPendingStandaloneConversation(id: Long)
    fun promoteFloatingConversation(id: Long)
    fun discardFloatingConversation(id: Long)
    fun toggleConversationPinned(id: Long)
    fun deleteConversation(id: Long)
    /** Immediate cancellation for UI disposal; close additionally awaits child jobs. */
    fun cancel()
    suspend fun close()
}

fun interface ConversationSessionFactory {
    fun create(parentScope: CoroutineScope): ConversationSession
}
