@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.HistoryMessageWrite
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.StoredMessage
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.HistoryRepositoryResource
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit ephemeral provider; every mount allocates an independent, fully functional repository. */
fun memoryHistoryRepositoryFactory(): HistoryRepositoryFactory = HistoryRepositoryFactory {
    val repository = MemoryHistoryRepository()
    HistoryRepositoryResource(repository) { repository.close() }
}

internal class MemoryHistoryRepository : ConversationHistoryRepository {
    private val mutex = Mutex()
    private var closed = false
    private var timestamp = 0L
    private var largestId = 0L
    private val conversations = mutableMapOf<Long, StoredConversation>()
    private val deleted = linkedSetOf<Long>()
    private val tasks = mutableMapOf<String, ScheduledTask>()

    private suspend fun <T> access(block: () -> T): T = mutex.withLock {
        check(!closed) { "memory history repository is closed" }
        block()
    }

    private fun now(): Long {
        timestamp = maxOf(timestamp + 1, Clock.System.now().toEpochMilliseconds())
        return timestamp
    }

    private fun conversation(id: Long, title: String, at: Long): StoredConversation {
        largestId = maxOf(largestId, id)
        return conversations[id] ?: StoredConversation(id, title, at, at, messages = emptyList())
    }

    override suspend fun loadAll(): List<StoredConversation> = access {
        conversations.values.filter { it.id !in deleted }
            .sortedWith(compareByDescending<StoredConversation> { it.isPinned }.thenByDescending { it.updatedAt }.thenByDescending { it.id })
            .map { it.copy(messages = it.messages.toList()) }
    }

    override suspend fun nextConversationId(): Long = access { largestId + 1 }

    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) = appendMessages(conversationId, title, listOf(HistoryMessageWrite(messageId, role, content, isError)))

    override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) = access {
        require(messages.map { it.id }.distinct().size == messages.size) { "Duplicate message ids in batch" }
        if (messages.isNotEmpty()) {
            val at = now()
            val previous = conversation(conversationId, title, at)
            val merged = previous.messages.associateBy { it.id }.toMutableMap()
            messages.forEach { message ->
                merged[message.id] = StoredMessage(message.id, conversationId, message.role, message.content, message.isError, now())
            }
            conversations[conversationId] = previous.copy(
                title = title, updatedAt = timestamp,
                messages = merged.values.sortedWith(compareBy<StoredMessage> { it.createdAt }.thenBy { it.id }),
            )
        }
    }

    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) = access {
        conversations[conversationId]?.let { row ->
            conversations[conversationId] = row.copy(messages = row.messages.filter { it.id < messageIdInclusive })
        }
        Unit
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) = access {
        conversations[conversationId]?.let { row -> conversations[conversationId] = row.copy(isPinned = pinned, updatedAt = now()) }
        Unit
    }

    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) = access {
        val at = now()
        conversations[conversationId] = conversation(conversationId, title, at).copy(title = title, goal = goal, updatedAt = at)
    }

    override suspend fun clearGoal(conversationId: Long) = access {
        conversations[conversationId]?.let { row -> conversations[conversationId] = row.copy(goal = null) }
        Unit
    }

    override suspend fun createConversation(conversationId: Long, title: String, presentation: ConversationPresentation) = access {
        if (conversationId !in conversations) {
            val at = now()
            conversations[conversationId] = conversation(conversationId, title, at).copy(presentation = presentation)
        }
    }

    override suspend fun setConversationPresentation(conversationId: Long, presentation: ConversationPresentation) = access {
        conversations[conversationId]?.let { row -> conversations[conversationId] = row.copy(presentation = presentation, updatedAt = now()) }
        Unit
    }

    override suspend fun setStandaloneResult(conversationId: Long, result: String) = access {
        conversations[conversationId]?.let { row -> conversations[conversationId] = row.copy(standaloneResult = result, updatedAt = now()) }
        Unit
    }

    override suspend fun deleteConversation(conversationId: Long) = access {
        if (conversationId in conversations) {
            deleted.remove(conversationId)
            deleted.add(conversationId)
            tasks.entries.removeAll { it.value.conversationId == conversationId }
            while (deleted.size > 100) conversations.remove(deleted.first().also { deleted.remove(it) })
        }
    }

    override suspend fun loadScheduledTasks(conversationId: Long?): List<ScheduledTask> = access {
        tasks.values.filter { it.conversationId !in deleted && (conversationId == null || it.conversationId == conversationId) }
            .sortedWith(compareBy<ScheduledTask> { it.nextRunAt }.thenBy { it.createdAt }.thenBy { it.taskId })
    }

    override suspend fun upsertScheduledTask(title: String, task: ScheduledTask) = access {
        conversations[task.conversationId] = conversation(task.conversationId, title, now())
        tasks[task.taskId] = task
    }

    override suspend fun updateScheduledTask(task: ScheduledTask) = access {
        if (task.taskId in tasks) tasks[task.taskId] = task
    }

    override suspend fun deleteScheduledTask(conversationId: Long, taskId: String) = access {
        if (tasks[taskId]?.conversationId == conversationId) tasks.remove(taskId)
        Unit
    }

    suspend fun close() = mutex.withLock {
        closed = true
        conversations.clear()
        tasks.clear()
        deleted.clear()
    }
}
