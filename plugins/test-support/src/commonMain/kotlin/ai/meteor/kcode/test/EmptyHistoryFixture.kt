package ai.meteor.kcode.test

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.HistoryMessageWrite
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.ThreadGoal

/** Explicit no-op fixture for focused tests; never included in a native production bundle. */
class EmptyHistoryFixture : ConversationHistoryRepository {
    private val scheduledTasks = mutableMapOf<String, ScheduledTask>()

    override suspend fun nextConversationId(): Long = 1L

    override suspend fun createConversation(conversationId: Long, title: String, presentation: ConversationPresentation) = Unit

    override suspend fun setConversationPresentation(conversationId: Long, presentation: ConversationPresentation) = Unit

    override suspend fun setStandaloneResult(conversationId: Long, result: String) = Unit

    override suspend fun loadAll(): List<StoredConversation> = emptyList()

    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) = Unit

    override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) = Unit

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
