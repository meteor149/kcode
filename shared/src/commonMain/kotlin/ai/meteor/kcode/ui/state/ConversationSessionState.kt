package ai.meteor.kcode.ui.state

import ai.meteor.kcode.history.StoredMessage
import ai.meteor.kcode.history.ConversationHistoryRepository
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
) {
    var title by mutableStateOf(initialTitle)
    var isPinned by mutableStateOf(initialPinned)
    var goal by mutableStateOf(initialGoal)
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
    var activeId by mutableStateOf<Long?>(null)
        private set
    private var sequence = 1L

    suspend fun load() {
        runCatching { historyRepository.loadAll() }
            .onSuccess { storedConversations ->
                if (conversations.isEmpty()) {
                    conversations += storedConversations.map { stored ->
                        ConversationState(stored.id, stored.title, stored.isPinned, stored.goal).apply {
                            messages += stored.messages.map(StoredMessage::toChatMessage)
                        }
                    }
                    activeId = storedConversations.firstOrNull()?.id
                }
                sequence = maxOf(sequence, (storedConversations.maxOfOrNull { it.id } ?: 0L) + 1L)
            }
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
