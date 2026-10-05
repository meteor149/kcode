package ai.meteor.kcode.session

import ai.meteor.kcode.ui.state.ConversationState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex

/** The provider owns one data plane; each consumer owns its scope and execution jobs. */
internal class HistoryConversationData {
    val conversations = mutableStateListOf<ConversationState>()
    val floatingConversations = mutableStateListOf<ConversationState>()
    val pendingStandaloneConversations = mutableMapOf<Long, ConversationState>()
    val mutations = Mutex()
    var sequence = 1L
    var isLoaded by mutableStateOf(false)
    var failureMessage by mutableStateOf<String?>(null)
}
