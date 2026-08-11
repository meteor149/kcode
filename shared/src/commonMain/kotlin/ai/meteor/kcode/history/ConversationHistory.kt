package ai.meteor.kcode.history

/** Platform-neutral snapshot consumed by the shared Compose UI. */
data class StoredConversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val goal: ThreadGoal? = null,
    val messages: List<StoredMessage>,
)

enum class ThreadGoalStatus { Active, Paused, Blocked, UsageLimited, BudgetLimited, Complete }

data class ThreadGoal(
    val goalId: String,
    val objective: String,
    val status: ThreadGoalStatus,
    val tokenBudget: Long? = null,
    val tokensUsed: Long = 0L,
    val timeUsedSeconds: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val remainingTokens: Long?
        get() = tokenBudget?.let { (it - tokensUsed).coerceAtLeast(0L) }
}

data class StoredMessage(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val isError: Boolean,
    val createdAt: Long,
)

interface ConversationHistoryRepository {
    suspend fun loadAll(): List<StoredConversation>

    /** Atomically creates/updates the conversation and stores the message. */
    suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean = false,
    )

    /** Removes [messageIdInclusive] and every later message in the conversation. */
    suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long)

    /** Moves a conversation into or out of the pinned section of recents. */
    suspend fun setPinned(conversationId: Long, pinned: Boolean)

    /** Creates or replaces the durable goal attached to a conversation. */
    suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal)

    /** Removes the durable goal attached to a conversation. */
    suspend fun clearGoal(conversationId: Long)

    /** Deletes the conversation and all of its messages. */
    suspend fun deleteConversation(conversationId: Long)
}

object TransientConversationHistoryRepository : ConversationHistoryRepository {
    override suspend fun loadAll(): List<StoredConversation> = emptyList()

    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) = Unit

    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) = Unit

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) = Unit

    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) = Unit

    override suspend fun clearGoal(conversationId: Long) = Unit

    override suspend fun deleteConversation(conversationId: Long) = Unit
}
