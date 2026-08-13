package ai.meteor.kcode.ui.state

import ai.meteor.kcode.history.StoredMessage
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.conversationTitle
import ai.meteor.kcode.model.decodeStoredMessageContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ConversationState(
    val id: Long,
    initialTitle: String,
    initialPinned: Boolean = false,
    initialGoal: ThreadGoal? = null,
    initialPresentation: ConversationPresentation = ConversationPresentation.Recent,
    initialStandaloneResult: String? = null,
) {
    var title by mutableStateOf(initialTitle)
    var isPinned by mutableStateOf(initialPinned)
    var goal by mutableStateOf(initialGoal)
    var presentation by mutableStateOf(initialPresentation)
    var standaloneResult by mutableStateOf(initialStandaloneResult)
    var shouldResumeGoal by mutableStateOf(initialGoal?.status == ai.meteor.kcode.history.ThreadGoalStatus.Active)
    val messages = mutableStateListOf<ChatMessage>()
    var isGenerating by mutableStateOf(false)
    var isAwaitingFirstToken by mutableStateOf(false)
    var runningJob: Job? = null
}

internal class ConversationSessionState(
    private val historyRepository: ConversationHistoryRepository,
    private val scope: CoroutineScope,
) {
    val conversations = mutableStateListOf<ConversationState>()
    val floatingConversations = mutableStateListOf<ConversationState>()
    private val pendingStandaloneConversations = mutableMapOf<Long, ConversationState>()
    var activeId by mutableStateOf<Long?>(null)
        private set
    var isLoaded by mutableStateOf(false)
        private set
    private var sequence = 1L

    suspend fun load() {
        runCatching { historyRepository.loadAll() }
            .onSuccess { storedConversations ->
                if (conversations.isEmpty() && floatingConversations.isEmpty()) {
                    val loaded = storedConversations.map { stored ->
                        ConversationState(
                            stored.id,
                            stored.title,
                            stored.isPinned,
                            stored.goal,
                            stored.presentation,
                            stored.standaloneResult,
                        ).apply {
                            messages += stored.messages.map(StoredMessage::toChatMessage)
                        }
                    }
                    conversations += loaded.filter { it.presentation == ConversationPresentation.Recent }
                    floatingConversations += loaded.filter { it.presentation == ConversationPresentation.Floating }
                    loaded.filter { it.presentation == ConversationPresentation.PendingStandalone }.forEach { pending ->
                        if (pending.messages.lastOrNull()?.role == MessageRole.Assistant) {
                            pending.presentation = ConversationPresentation.Floating
                            floatingConversations += pending
                            runCatching {
                                historyRepository.setConversationPresentation(
                                    pending.id,
                                    ConversationPresentation.Floating,
                                )
                            }
                        } else {
                            runCatching { historyRepository.deleteConversation(pending.id) }
                        }
                    }
                    loaded.filter { it.presentation != ConversationPresentation.PendingStandalone }
                        .forEach { conversation -> ensureStandaloneResultMessage(conversation) }
                    activeId = conversations.firstOrNull()?.id
                }
            }
        sequence = maxOf(sequence, runCatching { historyRepository.nextConversationId() }.getOrDefault(sequence))
        isLoaded = true
    }

    fun selectConversation(id: Long) {
        activeId = id
    }

    fun startNewConversation() {
        activeId = null
    }

    fun ensureConversation(prompt: String): ConversationState {
        conversations.firstOrNull { it.id == activeId }?.let { return it }
        val created = ConversationState(sequence++, conversationTitle(prompt))
        val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
        conversations.add(if (firstUnpinned < 0) conversations.size else firstUnpinned, created)
        activeId = created.id
        return created
    }

    suspend fun createPendingStandaloneConversation(title: String): ConversationState {
        val id = maxOf(sequence, historyRepository.nextConversationId())
        sequence = id + 1L
        val created = ConversationState(
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
        return created
    }

    suspend fun revealStandaloneConversation(id: Long) {
        val conversation = pendingStandaloneConversations.remove(id) ?: return
        historyRepository.setConversationPresentation(id, ConversationPresentation.Floating)
        conversation.presentation = ConversationPresentation.Floating
        floatingConversations += conversation
    }

    suspend fun setPendingStandaloneResult(id: Long, result: String) {
        val normalized = result.trim()
        require(normalized.isNotEmpty()) { "scheduled task result must not be empty" }
        val conversation = pendingStandaloneConversations[id]
            ?: error("standalone scheduled-task conversation does not exist: $id")
        historyRepository.setStandaloneResult(id, normalized)
        conversation.standaloneResult = normalized
    }

    suspend fun appendPendingStandaloneResultMessage(id: Long) {
        val conversation = pendingStandaloneConversations[id]
            ?: error("standalone scheduled-task conversation does not exist: $id")
        ensureStandaloneResultMessage(conversation)
    }

    suspend fun discardPendingStandaloneConversation(id: Long) {
        if (pendingStandaloneConversations.remove(id) != null) {
            historyRepository.deleteConversation(id)
        }
    }

    fun promoteFloatingConversation(id: Long) {
        val index = floatingConversations.indexOfFirst { it.id == id }
        if (index < 0) return
        val conversation = floatingConversations.removeAt(index)
        conversation.presentation = ConversationPresentation.Recent
        val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
        conversations.add(if (firstUnpinned < 0) conversations.size else firstUnpinned, conversation)
        activeId = id
        scope.launch {
            runCatching { historyRepository.setConversationPresentation(id, ConversationPresentation.Recent) }
        }
    }

    fun discardFloatingConversation(id: Long) {
        val index = floatingConversations.indexOfFirst { it.id == id }
        if (index < 0) return
        val conversation = floatingConversations.removeAt(index)
        scope.launch {
            conversation.runningJob?.let { job ->
                job.cancel()
                job.join()
            }
            runCatching { historyRepository.deleteConversation(id) }
        }
    }

    fun toggleConversationPinned(id: Long) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return
        val conversation = conversations.removeAt(index)
        val pinned = !conversation.isPinned
        conversation.isPinned = pinned
        if (pinned) {
            conversations.add(0, conversation)
        } else {
            val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
            conversations.add(if (firstUnpinned < 0) conversations.size else firstUnpinned, conversation)
        }
        scope.launch { runCatching { historyRepository.setPinned(id, pinned) } }
    }

    fun deleteConversation(id: Long) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return
        val conversation = conversations.removeAt(index)
        if (activeId == id) activeId = null
        scope.launch {
            conversation.runningJob?.let { job ->
                job.cancel()
                job.join()
            }
            runCatching { historyRepository.deleteConversation(id) }
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
            content = message.content,
        )
        conversation.messages += message
    }
}

@Composable
internal fun rememberConversationSessionState(
    historyRepository: ConversationHistoryRepository,
): ConversationSessionState {
    val scope = rememberCoroutineScope()
    val state = remember(historyRepository) { ConversationSessionState(historyRepository, scope) }
    LaunchedEffect(state) { state.load() }
    return state
}

internal fun StoredMessage.toChatMessage(): ChatMessage {
    val decoded = decodeStoredMessageContent(content)
    return ChatMessage(
        id = id,
        role = if (role == MessageRole.User.name) MessageRole.User else MessageRole.Assistant,
        content = decoded.text,
        isError = isError,
        toolUses = decoded.toolUses,
        subAgents = decoded.subAgents,
    )
}
