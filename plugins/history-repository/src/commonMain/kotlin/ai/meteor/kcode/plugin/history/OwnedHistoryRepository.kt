package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.HistoryMessageWrite
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.plugin.api.PluginOperationOwner

internal class OwnedHistoryRepository(
    private val delegate: ConversationHistoryRepository,
    private val owner: PluginOperationOwner,
) : ConversationHistoryRepository {
    override suspend fun loadAll(): List<StoredConversation> = owner.run { delegate.loadAll() }
    override suspend fun nextConversationId(): Long = owner.run { delegate.nextConversationId() }
    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) = owner.run { delegate.appendMessage(conversationId, title, messageId, role, content, isError) }
    override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) =
        owner.run { delegate.appendMessages(conversationId, title, messages) }
    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) =
        owner.run { delegate.deleteMessagesFrom(conversationId, messageIdInclusive) }
    override suspend fun setPinned(conversationId: Long, pinned: Boolean) = owner.run { delegate.setPinned(conversationId, pinned) }
    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) = owner.run { delegate.setGoal(conversationId, title, goal) }
    override suspend fun clearGoal(conversationId: Long) = owner.run { delegate.clearGoal(conversationId) }
    override suspend fun deleteConversation(conversationId: Long) = owner.run { delegate.deleteConversation(conversationId) }
    override suspend fun createConversation(conversationId: Long, title: String, presentation: ConversationPresentation) =
        owner.run { delegate.createConversation(conversationId, title, presentation) }
    override suspend fun setConversationPresentation(conversationId: Long, presentation: ConversationPresentation) =
        owner.run { delegate.setConversationPresentation(conversationId, presentation) }
    override suspend fun setStandaloneResult(conversationId: Long, result: String) = owner.run { delegate.setStandaloneResult(conversationId, result) }
    override suspend fun loadScheduledTasks(conversationId: Long?): List<ScheduledTask> = owner.run { delegate.loadScheduledTasks(conversationId) }
    override suspend fun upsertScheduledTask(title: String, task: ScheduledTask) = owner.run { delegate.upsertScheduledTask(title, task) }
    override suspend fun updateScheduledTask(task: ScheduledTask) = owner.run { delegate.updateScheduledTask(task) }
    override suspend fun deleteScheduledTask(conversationId: Long, taskId: String) = owner.run { delegate.deleteScheduledTask(conversationId, taskId) }
}
