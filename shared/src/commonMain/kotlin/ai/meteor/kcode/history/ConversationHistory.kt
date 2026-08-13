package ai.meteor.kcode.history

/** Platform-neutral snapshot consumed by the shared Compose UI. */
data class StoredConversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val presentation: ConversationPresentation = ConversationPresentation.Recent,
    val standaloneResult: String? = null,
    val goal: ThreadGoal? = null,
    val messages: List<StoredMessage>,
)

enum class ConversationPresentation { Recent, PendingStandalone, Floating }

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

enum class ScheduledTaskStatus { Active, Paused, Completed }

data class ScheduledTask(
    val taskId: String,
    val conversationId: Long,
    val name: String,
    val prompt: String,
    val status: ScheduledTaskStatus,
    val nextRunAt: Long,
    val repeatIntervalMillis: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
)

interface ConversationHistoryRepository {
    suspend fun loadAll(): List<StoredConversation>

    /** Returns an id larger than every conversation, including soft-deleted rows. */
    suspend fun nextConversationId(): Long = (loadAll().maxOfOrNull(StoredConversation::id) ?: 0L) + 1L

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

    /** Soft-deletes the conversation; old deleted rows may be pruned once the retention limit is exceeded. */
    suspend fun deleteConversation(conversationId: Long)

    /** Persists an empty conversation before its first message is produced. */
    suspend fun createConversation(
        conversationId: Long,
        title: String,
        presentation: ConversationPresentation = ConversationPresentation.Recent,
    ) = Unit

    /** Moves a conversation between the recent list and the standalone-task floating layer. */
    suspend fun setConversationPresentation(
        conversationId: Long,
        presentation: ConversationPresentation,
    ) = Unit

    /** Stores the user-facing result selected by the LLM for a standalone scheduled task. */
    suspend fun setStandaloneResult(conversationId: Long, result: String) = Unit

    /** Loads durable scheduled tasks, optionally scoped to one conversation. */
    suspend fun loadScheduledTasks(conversationId: Long? = null): List<ScheduledTask> = emptyList()

    /** Creates or replaces a durable scheduled task and ensures its conversation exists. */
    suspend fun upsertScheduledTask(title: String, task: ScheduledTask) = Unit

    /** Updates an existing task without recreating a deleted conversation. */
    suspend fun updateScheduledTask(task: ScheduledTask) = Unit

    /** Deletes a scheduled task only when it belongs to [conversationId]. */
    suspend fun deleteScheduledTask(conversationId: Long, taskId: String) = Unit
}

object TransientConversationHistoryRepository : ConversationHistoryRepository {
    private val scheduledTasks = mutableMapOf<String, ScheduledTask>()

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

    override suspend fun deleteConversation(conversationId: Long) {
        scheduledTasks.entries.removeAll { it.value.conversationId == conversationId }
    }

    override suspend fun loadScheduledTasks(conversationId: Long?): List<ScheduledTask> = scheduledTasks.values
        .filter { conversationId == null || it.conversationId == conversationId }
        .sortedBy(ScheduledTask::nextRunAt)

    override suspend fun upsertScheduledTask(title: String, task: ScheduledTask) {
        scheduledTasks[task.taskId] = task
    }

    override suspend fun updateScheduledTask(task: ScheduledTask) {
        if (task.taskId in scheduledTasks) scheduledTasks[task.taskId] = task
    }

    override suspend fun deleteScheduledTask(conversationId: Long, taskId: String) {
        scheduledTasks[taskId]?.takeIf { it.conversationId == conversationId }?.let { scheduledTasks.remove(taskId) }
    }
}
