package ai.meteor.kcode.session

import ai.meteor.kcode.ui.state.ConversationState

import kotlinx.coroutines.flow.MutableStateFlow
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.model.ChatMessage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job

class HistoryConversationState(
    override val id: Long,
    initialTitle: String,
    initialPinned: Boolean = false,
    initialGoal: ThreadGoal? = null,
    initialPresentation: ConversationPresentation = ConversationPresentation.Recent,
    initialStandaloneResult: String? = null,
) : ConversationState {
    private val reservedMessageId = MutableStateFlow(0L)

    /** Reserve IDs before suspension so pending command feedback cannot collide with generation events. */
    override fun reserveMessageIds(count: Int): Long {
        require(count > 0)
        while (true) {
            val previous = reservedMessageId.value
            val latest = maxOf(previous, messages.maxOfOrNull { it.id } ?: 0L)
            check(latest <= Long.MAX_VALUE - count) { "Conversation message ids are exhausted" }
            val first = latest + 1L
            if (reservedMessageId.compareAndSet(previous, latest + count)) return first
        }
    }

    override var title by mutableStateOf(initialTitle)
    override var isPinned by mutableStateOf(initialPinned)
    override var goal by mutableStateOf(initialGoal)
    override var presentation by mutableStateOf(initialPresentation)
    override var standaloneResult by mutableStateOf(initialStandaloneResult)
    override var shouldResumeGoal by mutableStateOf(initialGoal?.status == ai.meteor.kcode.history.ThreadGoalStatus.Active)
    override val messages = mutableStateListOf<ChatMessage>()
    /** Transient execution failures are not committed transcript messages. */
    override var executionFailure by mutableStateOf<String?>(null)
    override var isGenerating by mutableStateOf(false)
    override var isAwaitingFirstToken by mutableStateOf(false)
    override var runningJob: Job? = null
}
