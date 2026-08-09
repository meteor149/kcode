package ai.meteor.kcode.ui.pages.chat


import ai.meteor.kcode.ui.state.ConversationState

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatServiceUnavailable
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import ai.meteor.kcode.model.toStoredContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChatFailureMessages(
    val setupModel: String,
    val connectionFailed: String,
    val unavailable: String?,
)

internal fun sendMessage(
    prompt: String,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    onSendToNew: (String) -> ConversationState,
    service: ChatService,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    shouldFollowLatest: Boolean,
    onFollowLatestChange: (Boolean) -> Unit,
    followBottom: (ConversationState) -> Unit,
) {
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isEmpty()) return
    val target = conversation ?: onSendToNew(cleanPrompt)
    if (target.isGenerating) return
    onFollowLatestChange(shouldFollowLatest)

    val userMessage = ChatMessage(
        id = nextMessageId(target),
        role = MessageRole.User,
        content = cleanPrompt,
    )
    if (configuration == null) {
        appendSetupRequiredMessages(
            target = target,
            userMessage = userMessage,
            setupMessage = failureMessages.setupModel,
            historyRepository = historyRepository,
            scope = scope,
            followBottom = followBottom,
        )
        return
    }

    val history = target.messages.toList()
    target.messages += userMessage
    val assistantId = userMessage.id + 1L
    prepareStreamingResponse(target, assistantId)
    followBottom(target)
    launchStreamingResponse(
        target = target,
        assistantId = assistantId,
        configuration = configuration,
        history = history,
        prompt = cleanPrompt,
        service = service,
        historyRepository = historyRepository,
        scope = scope,
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = {
            historyRepository.appendMessage(
                conversationId = target.id,
                title = target.title,
                messageId = userMessage.id,
                role = userMessage.role.name,
                content = userMessage.content,
            )
        },
    )
}

internal fun regenerateMessage(
    answer: ChatMessage,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    service: ChatService,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    shouldFollowLatest: Boolean,
    onFollowLatestChange: (Boolean) -> Unit,
    followBottom: (ConversationState) -> Unit,
) {
    val activeConfiguration = configuration ?: return
    val target = conversation ?: return
    if (target.isGenerating) return
    val answerIndex = target.messages.indexOfFirst { it.id == answer.id }
    if (answerIndex < 0 || answer.role != MessageRole.Assistant) return
    val promptIndex = (answerIndex - 1 downTo 0)
        .firstOrNull { target.messages[it].role == MessageRole.User }
        ?: return
    val prompt = target.messages[promptIndex].content
    val history = target.messages.take(promptIndex)
    onFollowLatestChange(shouldFollowLatest)
    target.messages.subList(answerIndex, target.messages.size).clear()
    val replacementId = nextMessageId(target)
    prepareStreamingResponse(target, replacementId)
    followBottom(target)
    launchStreamingResponse(
        target = target,
        assistantId = replacementId,
        configuration = activeConfiguration,
        history = history,
        prompt = prompt,
        service = service,
        historyRepository = historyRepository,
        scope = scope,
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = { historyRepository.deleteMessagesFrom(target.id, answer.id) },
    )
}

private fun appendSetupRequiredMessages(
    target: ConversationState,
    userMessage: ChatMessage,
    setupMessage: String,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    followBottom: (ConversationState) -> Unit,
) {
    val assistantMessage = ChatMessage(
        id = userMessage.id + 1L,
        role = MessageRole.Assistant,
        content = setupMessage,
    )
    target.messages += userMessage
    target.messages += assistantMessage
    followBottom(target)
    scope.launch {
        runCatching {
            historyRepository.appendMessage(
                conversationId = target.id,
                title = target.title,
                messageId = userMessage.id,
                role = userMessage.role.name,
                content = userMessage.content,
            )
            historyRepository.appendMessage(
                conversationId = target.id,
                title = target.title,
                messageId = assistantMessage.id,
                role = assistantMessage.role.name,
                content = assistantMessage.content,
            )
        }
    }
}

private fun prepareStreamingResponse(target: ConversationState, assistantId: Long) {
    target.isGenerating = true
    target.isAwaitingFirstToken = true
    target.messages += ChatMessage(assistantId, MessageRole.Assistant, "")
}

private fun launchStreamingResponse(
    target: ConversationState,
    assistantId: Long,
    configuration: ModelConfiguration,
    history: List<ChatMessage>,
    prompt: String,
    service: ChatService,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    followBottom: (ConversationState) -> Unit,
    beforeRequest: suspend () -> Unit,
) {
    target.runningJob = scope.launch {
        runCatching { beforeRequest() }
        try {
            val answer = service.replyStreaming(
                configuration = configuration,
                history = history,
                prompt = prompt,
                onToolUse = { event ->
                    target.isAwaitingFirstToken = false
                    target.applyToolUseEvent(assistantId, event)
                    followBottom(target)
                },
                onDelta = { delta ->
                    if (delta.isNotEmpty()) {
                        target.isAwaitingFirstToken = false
                        target.updateMessage(assistantId) { it.copy(content = it.content + delta) }
                        followBottom(target)
                    }
                },
            )
            target.isAwaitingFirstToken = false
            target.updateMessage(assistantId) {
                if (it.content.isBlank() && answer.isNotBlank()) it.copy(content = answer) else it
            }
            val assistantMessage = target.messages.first { it.id == assistantId }
            runCatching {
                historyRepository.appendMessage(
                    conversationId = target.id,
                    title = target.title,
                    messageId = assistantMessage.id,
                    role = assistantMessage.role.name,
                    content = assistantMessage.toStoredContent(),
                )
            }
        } catch (cancelled: CancellationException) {
            target.persistOrRemovePartialMessage(assistantId, historyRepository)
            throw cancelled
        } catch (error: Throwable) {
            target.persistOrRemovePartialMessage(assistantId, historyRepository)
            val safeDetail = error.message?.replace(configuration.apiKey, "••••")
            val errorMessage = ChatMessage(
                id = nextMessageId(target),
                role = MessageRole.Assistant,
                content = if (error is ChatServiceUnavailable) {
                    failureMessages.unavailable ?: failureMessages.connectionFailed
                } else {
                    safeDetail ?: failureMessages.connectionFailed
                },
                isError = true,
            )
            target.messages += errorMessage
            followBottom(target)
            runCatching {
                historyRepository.appendMessage(
                    conversationId = target.id,
                    title = target.title,
                    messageId = errorMessage.id,
                    role = errorMessage.role.name,
                    content = errorMessage.content,
                    isError = true,
                )
            }
        } finally {
            target.isGenerating = false
            target.isAwaitingFirstToken = false
            target.runningJob = null
        }
    }
}

private fun nextMessageId(conversation: ConversationState): Long =
    (conversation.messages.maxOfOrNull { it.id } ?: 0L) + 1L

private inline fun ConversationState.updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
    val index = messages.indexOfFirst { it.id == id }
    if (index >= 0) messages[index] = transform(messages[index])
}

private fun ConversationState.applyToolUseEvent(messageId: Long, event: ToolUseEvent) {
    updateMessage(messageId) { message ->
        when (event) {
            is ToolUseEvent.Started -> {
                val toolUse = ToolUseInfo(
                    id = event.id,
                    name = event.name,
                    input = event.input,
                    textOffset = message.content.length,
                )
                message.copy(toolUses = message.toolUses.filterNot { it.id == event.id } + toolUse)
            }
            is ToolUseEvent.Finished -> message.copy(
                toolUses = message.toolUses.map { toolUse ->
                    if (toolUse.id != event.id) toolUse else toolUse.copy(
                        output = event.output,
                        status = if (event.isError) ToolUseStatus.Failed else ToolUseStatus.Succeeded,
                    )
                },
            )
        }
    }
}

private suspend fun ConversationState.persistOrRemovePartialMessage(
    messageId: Long,
    historyRepository: ConversationHistoryRepository,
) = withContext(NonCancellable) {
    val partial = messages.firstOrNull { it.id == messageId }
    if (partial == null || (partial.content.isBlank() && partial.toolUses.isEmpty())) {
        messages.removeAll { it.id == messageId }
        return@withContext
    }
    runCatching {
        historyRepository.appendMessage(
            conversationId = id,
            title = title,
            messageId = partial.id,
            role = partial.role.name,
            content = partial.toStoredContent(),
            isError = partial.isError,
        )
    }
}
