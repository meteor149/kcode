package ai.meteor.kcode.chat

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.ui.state.ConversationState

/** Grammar and behavior belong to the contribution, including generation-time availability. */
interface ConversationCommand {
    val allowedDuringGeneration: Boolean
    suspend fun execute(request: ConversationCommandRequest)
}

data class ConversationCommandContribution(
    val id: String,
    val order: Int = 0,
    val match: (String) -> ConversationCommand?,
)

data class ConversationCommandSnapshot(val contributions: List<ConversationCommandContribution> = emptyList()) {
    fun resolve(prompt: String): ConversationCommand? = contributions.firstNotNullOfOrNull { it.match(prompt.trim()) }
    fun allowedDuringGeneration(prompt: String): Boolean = resolve(prompt)?.allowedDuringGeneration == true
}

/** Operations preserve the executor's history and generation lifetime; commands own domain decisions. */
interface ConversationCommandOperations {
    fun nextMessageId(target: ConversationState): Long
    suspend fun appendFeedback(target: ConversationState, user: ChatMessage, content: String, isError: Boolean = false)
    suspend fun startResponse(target: ConversationState, user: ChatMessage, prompt: String, goalSession: GoalSession? = null)
}

data class ConversationCommandRequest(
    val prompt: String,
    val language: AppLanguage,
    val conversation: ConversationState?,
    val onSendToNew: (String) -> ConversationState,
    val operations: ConversationCommandOperations,
)
