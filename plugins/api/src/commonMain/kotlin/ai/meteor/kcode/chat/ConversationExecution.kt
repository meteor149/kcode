package ai.meteor.kcode.chat

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.ui.state.ConversationState

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import kotlinx.coroutines.CoroutineScope


data class ChatFailureMessages(
    val setupModel: String,
    val connectionFailed: String,
)

/** A feature owns its response termination policy; the executor only reports lifecycle events. */
interface ConversationResponseLifecycle {
    suspend fun onCancelled() {}
    suspend fun onFailed() {}
}

/** Domain consumers prepare prompts and optional durable user input before submitting a response. */
data class ConversationResponseRequest(
    val prompt: String,
    val userMessage: String? = null,
    val goalSession: GoalSession? = null,
    val scheduledTaskSession: ScheduledTaskSession? = null,
    val scheduledTaskCompletionSession: ScheduledTaskCompletionSession? = null,
    val lifecycle: ConversationResponseLifecycle? = goalSession,
)

/** Replaceable orchestration; UI supplies inputs and callbacks, the provider owns persistence and jobs. */
interface ConversationExecution {

    fun sendMessage(
        prompt: String,
        configuration: ModelConfiguration?,
        conversation: ConversationState?,
        onSendToNew: (String) -> ConversationState,
        service: ChatService,
        generationRunner: ChatGenerationRunner,
        goalSessionFactory: GoalSessionFactory,
        scope: CoroutineScope,
        failureMessages: ChatFailureMessages,
        language: AppLanguage,
        scheduledTaskSessionFor: (ConversationState) -> ScheduledTaskSession? = { null },
        onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
        followBottom: (ConversationState) -> Unit,
    )

    fun startResponse(
        target: ConversationState,
        request: ConversationResponseRequest,
        configuration: ModelConfiguration?,
        service: ChatService,
        generationRunner: ChatGenerationRunner,
        failureMessages: ChatFailureMessages,
        followBottom: (ConversationState) -> Unit = {},
        onResponseFinished: suspend (completed: Boolean) -> Unit = {},
    ): Boolean

    fun regenerateMessage(
        answer: ChatMessage,
        configuration: ModelConfiguration?,
        conversation: ConversationState?,
        service: ChatService,
        generationRunner: ChatGenerationRunner,
        goalSessionFactory: GoalSessionFactory,
        scope: CoroutineScope,
        failureMessages: ChatFailureMessages,
        scheduledTaskSession: ScheduledTaskSession? = null,
        shouldFollowLatest: Boolean,
        onFollowLatestChange: (Boolean) -> Unit,
        followBottom: (ConversationState) -> Unit,
    )
}
