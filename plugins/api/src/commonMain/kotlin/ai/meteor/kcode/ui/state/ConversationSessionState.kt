package ai.meteor.kcode.ui.state

import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.model.ChatMessage
import kotlinx.coroutines.Job
import androidx.compose.runtime.Stable

/** Observable presentation contract; providers must notify Compose of property and message changes. */
@Stable
interface ConversationState {
    val id: Long
    var title: String
    var isPinned: Boolean
    var goal: ThreadGoal?
    var presentation: ConversationPresentation
    var standaloneResult: String?
    var shouldResumeGoal: Boolean
    val messages: MutableList<ChatMessage>
    var executionFailure: String?
    var isGenerating: Boolean
    var isAwaitingFirstToken: Boolean
    var runningJob: Job?

    /** Reserve before suspension so commands and generation events cannot collide. */
    fun reserveMessageIds(count: Int = 1): Long
}
