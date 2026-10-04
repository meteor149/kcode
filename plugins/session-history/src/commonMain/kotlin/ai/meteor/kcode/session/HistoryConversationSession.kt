package ai.meteor.kcode.session

import ai.meteor.kcode.history.StoredMessage
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ChatMessageCodec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob

import ai.meteor.kcode.chat.ConversationSession
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HistoryConversationSession(
    private val historyRepository: ConversationHistoryRepository,
    parentScope: CoroutineScope,
    private val messageCodec: ChatMessageCodec,
    private val onClosed: () -> Unit = {},
) : ConversationSession {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    override val conversations = mutableStateListOf<ConversationState>()
    override val floatingConversations = mutableStateListOf<ConversationState>()
    private val pendingStandaloneConversations = mutableMapOf<Long, ConversationState>()
    override var activeId by mutableStateOf<Long?>(null)
        private set
    override var isLoaded by mutableStateOf(false)
        private set
    private var sequence = 1L
    private var closed = false
    private val mutations = Mutex()
    override var failureMessage by mutableStateOf<String?>(null)
        private set

    override fun cancel() {
        if (closed) return
        closed = true
        allConversations().forEach { it.runningJob?.cancel() }
        scope.cancel()
    }

    override suspend fun close() {
        val running = allConversations().mapNotNull { it.runningJob }
        cancel()
        running.forEach { it.join() }
        scope.coroutineContext[Job]?.join()
        onClosed()
    }

    private fun allConversations(): List<ConversationState> =
        conversations + floatingConversations + pendingStandaloneConversations.values

    private fun requireOpen() = check(!closed) { "Conversation session provider has been disposed" }

    override suspend fun load() = mutate {
        requireOpen()
        if (isLoaded) return@mutate
        try {
            val stored = historyRepository.loadAll()
            val nextId = historyRepository.nextConversationId()
            val loaded = stored.map { value ->
                HistoryConversationState(
                    value.id, value.title, value.isPinned, value.goal,
                    value.presentation, value.standaloneResult,
                ).apply { messages += value.messages.map { it.toChatMessage(messageCodec) } }
            }.toMutableList()
            loaded.filter { it.presentation == ConversationPresentation.PendingStandalone }.forEach { pending ->
                if (pending.messages.lastOrNull()?.role == MessageRole.Assistant) {
                    historyRepository.setConversationPresentation(pending.id, ConversationPresentation.Floating)
                    pending.presentation = ConversationPresentation.Floating
                } else {
                    historyRepository.deleteConversation(pending.id)
                    loaded.remove(pending)
                }
            }
            loaded.forEach { ensureStandaloneResultMessage(it) }
            requireOpen()
            conversations.clear()
            conversations += loaded.filter { it.presentation == ConversationPresentation.Recent }
            floatingConversations.clear()
            floatingConversations += loaded.filter { it.presentation == ConversationPresentation.Floating }
            sequence = maxOf(sequence, nextId, (loaded.maxOfOrNull { it.id } ?: 0L) + 1L)
            activeId = conversations.firstOrNull()?.id
            failureMessage = null
            isLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failureMessage = error.message ?: "Conversation history load failed"
        }
    }

    override fun selectConversation(id: Long) {
        requireOpen()
        activeId = id
    }

    override fun startNewConversation() {
        requireOpen()
        activeId = null
    }

    override fun ensureConversation(prompt: String): ConversationState {
        requireOpen()
        conversations.firstOrNull { it.id == activeId }?.let { return it }
        val created = HistoryConversationState(sequence++, conversationTitle(prompt))
        val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
        conversations.add(if (firstUnpinned < 0) conversations.size else firstUnpinned, created)
        activeId = created.id
        return created
    }

    override suspend fun createPendingStandaloneConversation(title: String): ConversationState = mutate {
        requireOpen()
        val id = maxOf(sequence, historyRepository.nextConversationId())
        sequence = id + 1L
        val created = HistoryConversationState(
            id = id,
            initialTitle = title,
            initialPresentation = ConversationPresentation.PendingStandalone,
        )
        historyRepository.createConversation(
            created.id,
            created.title,
            ConversationPresentation.PendingStandalone,
        )
        pendingStandaloneConversations[created.id] = created
        created
    }

    override suspend fun revealStandaloneConversation(id: Long) = mutate {
        requireOpen()
        val conversation = pendingStandaloneConversations[id] ?: return@mutate
        historyRepository.setConversationPresentation(id, ConversationPresentation.Floating)
        pendingStandaloneConversations.remove(id)
        conversation.presentation = ConversationPresentation.Floating
        floatingConversations += conversation
    }

    override suspend fun setPendingStandaloneResult(id: Long, result: String) = mutate {
        requireOpen()
        val normalized = result.trim()
        require(normalized.isNotEmpty()) { "scheduled task result must not be empty" }
        val conversation = pendingStandaloneConversations[id]
            ?: error("standalone scheduled-task conversation does not exist: $id")
        historyRepository.setStandaloneResult(id, normalized)
        conversation.standaloneResult = normalized
    }

    override suspend fun appendPendingStandaloneResultMessage(id: Long) = mutate {
        requireOpen()
        val conversation = pendingStandaloneConversations[id]
            ?: error("standalone scheduled-task conversation does not exist: $id")
        ensureStandaloneResultMessage(conversation)
    }

    override suspend fun discardPendingStandaloneConversation(id: Long) = mutate {
        requireOpen()
        if (id in pendingStandaloneConversations) {
            historyRepository.deleteConversation(id)
            pendingStandaloneConversations.remove(id)
        }
    }

    override fun promoteFloatingConversation(id: Long) = mutateAsync {
        val conversation = floatingConversations.firstOrNull { it.id == id } ?: return@mutateAsync
        historyRepository.setConversationPresentation(id, ConversationPresentation.Recent)
        floatingConversations.remove(conversation)
        conversation.presentation = ConversationPresentation.Recent
        val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
        conversations.add(if (firstUnpinned < 0) conversations.size else firstUnpinned, conversation)
        activeId = id
    }

    override fun discardFloatingConversation(id: Long) = mutateAsync {
        val conversation = floatingConversations.firstOrNull { it.id == id } ?: return@mutateAsync
        conversation.runningJob?.let { it.cancel(); it.join() }
        historyRepository.deleteConversation(id)
        floatingConversations.remove(conversation)
    }

    override fun toggleConversationPinned(id: Long) = mutateAsync {
        val conversation = conversations.firstOrNull { it.id == id } ?: return@mutateAsync
        val pinned = !conversation.isPinned
        historyRepository.setPinned(id, pinned)
        conversations.remove(conversation)
        conversation.isPinned = pinned
        val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
        conversations.add(if (pinned) 0 else if (firstUnpinned < 0) conversations.size else firstUnpinned, conversation)
    }

    override fun deleteConversation(id: Long) = mutateAsync {
        val conversation = conversations.firstOrNull { it.id == id } ?: return@mutateAsync
        conversation.runningJob?.let { it.cancel(); it.join() }
        historyRepository.deleteConversation(id)
        conversations.remove(conversation)
        if (activeId == id) activeId = null
    }

    private suspend fun <T> mutate(action: suspend () -> T): T {
        requireOpen()
        val operation = scope.async { mutations.withLock { requireOpen(); action() } }
        return try {
            operation.await()
        } finally {
            operation.cancel()
        }
    }

    private fun mutateAsync(action: suspend () -> Unit) {
        requireOpen()
        scope.launch {
            mutations.withLock {
                try {
                    requireOpen()
                    action()
                    failureMessage = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failureMessage = error.message ?: "Conversation mutation failed"
                }
            }
        }
    }

    private suspend fun ensureStandaloneResultMessage(conversation: ConversationState) {
        val result = conversation.standaloneResult?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (conversation.messages.lastOrNull()?.let { message ->
                message.role == MessageRole.Assistant && message.content == result
            } == true
        ) {
            return
        }
        val message = ChatMessage(
            id = (conversation.messages.maxOfOrNull(ChatMessage::id) ?: 0L) + 1L,
            role = MessageRole.Assistant,
            content = result,
        )
        historyRepository.appendMessage(
            conversationId = conversation.id,
            title = conversation.title,
            messageId = message.id,
            role = message.role.name,
            content = messageCodec.encode(message),
        )
        conversation.messages += message
    }
}

private suspend fun StoredMessage.toChatMessage(messageCodec: ChatMessageCodec): ChatMessage {
    val decoded = messageCodec.decode(content)
    return ChatMessage(
        id = id,
        role = if (role == MessageRole.User.name) MessageRole.User else MessageRole.Assistant,
        content = decoded.text,
        isError = isError,
        toolUses = decoded.toolUses,
        subAgents = decoded.subAgents,
    )
}
