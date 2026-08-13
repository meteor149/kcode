package ai.meteor.kcode.ui.pages.chat


import ai.meteor.kcode.ui.state.ConversationState

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ConversationGoalSession
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.GoalCommand
import ai.meteor.kcode.chat.goalContinuationPrompt
import ai.meteor.kcode.chat.parseGoalCommand
import ai.meteor.kcode.chat.ChatServiceUnavailable
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.scheduledTaskExecutionPrompt
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.model.SubAgentRunStatus
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

internal data class GoalCommandMessages(
    val noGoal: String,
    val cleared: String,
    val objectiveRequired: String,
    val summarize: (ThreadGoal) -> String,
)

internal fun sendMessage(
    prompt: String,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    onSendToNew: (String) -> ConversationState,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    goalMessages: GoalCommandMessages,
    scheduledTaskSessionFor: (ConversationState) -> ScheduledTaskSession? = { null },
    onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
    followBottom: (ConversationState) -> Unit,
) {
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isEmpty()) return
    val goalCommand = parseGoalCommand(cleanPrompt)
    if (goalCommand != null) {
        handleGoalCommand(
            commandText = cleanPrompt,
            command = goalCommand,
            configuration = configuration,
            conversation = conversation,
            onSendToNew = onSendToNew,
            service = service,
            generationRunner = generationRunner,
            historyRepository = historyRepository,
            scope = scope,
            failureMessages = failureMessages,
            goalMessages = goalMessages,
            scheduledTaskSessionFor = scheduledTaskSessionFor,
            onUserMessageAdded = onUserMessageAdded,
            followBottom = followBottom,
        )
        return
    }
    val target = conversation ?: onSendToNew(cleanPrompt)
    if (target.isGenerating) return
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
            onUserMessageAdded = onUserMessageAdded,
        )
        return
    }

    val history = target.messages.toList()
    target.messages += userMessage
    val assistantId = userMessage.id + 1L
    prepareStreamingResponse(target, assistantId)
    onUserMessageAdded(target, userMessage)
    launchStreamingResponse(
        target = target,
        assistantId = assistantId,
        configuration = configuration,
        history = history,
        prompt = cleanPrompt,
        service = service,
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        goalSession = ConversationGoalSession(target, historyRepository),
        scheduledTaskSession = scheduledTaskSessionFor(target),
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

private fun handleGoalCommand(
    commandText: String,
    command: GoalCommand,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    onSendToNew: (String) -> ConversationState,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    goalMessages: GoalCommandMessages,
    scheduledTaskSessionFor: (ConversationState) -> ScheduledTaskSession?,
    onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
    followBottom: (ConversationState) -> Unit,
) {
    val titleSeed = when (command) {
        is GoalCommand.Set -> command.objective
        is GoalCommand.Edit -> command.objective
        else -> commandText
    }
    val target = conversation ?: onSendToNew(titleSeed)
    val userMessage = ChatMessage(nextMessageId(target), MessageRole.User, commandText)
    val session = ConversationGoalSession(target, historyRepository)

    fun appendFeedback(content: String, isError: Boolean = false) {
        val assistant = ChatMessage(userMessage.id + 1L, MessageRole.Assistant, content, isError)
        target.messages += userMessage
        target.messages += assistant
        onUserMessageAdded(target, userMessage)
        followBottom(target)
        scope.launch {
            persistMessage(target, userMessage, historyRepository)
            persistMessage(target, assistant, historyRepository)
        }
    }

    when (command) {
        GoalCommand.Show -> appendFeedback(target.goal?.let(goalMessages.summarize) ?: goalMessages.noGoal)
        GoalCommand.Pause -> {
            val goal = target.goal
            if (goal == null) {
                appendFeedback(goalMessages.noGoal, isError = true)
            } else {
                target.runningJob?.cancel()
                scope.launch {
                    target.runningJob?.join()
                    val updated = session.setStatusFromUser(ThreadGoalStatus.Paused)
                    appendFeedback(goalMessages.summarize(updated))
                }
            }
        }
        GoalCommand.Clear -> {
            if (target.goal == null) {
                appendFeedback(goalMessages.noGoal, isError = true)
            } else {
                target.runningJob?.cancel()
                scope.launch {
                    target.runningJob?.join()
                    session.clearGoal()
                    appendFeedback(goalMessages.cleared)
                }
            }
        }
        GoalCommand.Resume -> {
            if (target.goal == null) {
                appendFeedback(goalMessages.noGoal, isError = true)
            } else {
                startGoalRun(
                    target = target,
                    userMessage = userMessage,
                    mutateGoal = { session.setStatusFromUser(ThreadGoalStatus.Active) },
                    configuration = configuration,
                    service = service,
                    generationRunner = generationRunner,
                    historyRepository = historyRepository,
                    session = session,
                    scheduledTaskSession = scheduledTaskSessionFor(target),
                    scope = scope,
                    failureMessages = failureMessages,
                    onUserMessageAdded = onUserMessageAdded,
                    followBottom = followBottom,
                )
            }
        }
        is GoalCommand.Set -> {
            if (command.objective.isBlank()) {
                appendFeedback(goalMessages.objectiveRequired, isError = true)
            } else {
                target.runningJob?.cancel()
                startGoalRun(
                    target = target,
                    userMessage = userMessage,
                    mutateGoal = { session.setGoalFromUser(command.objective) },
                    configuration = configuration,
                    service = service,
                    generationRunner = generationRunner,
                    historyRepository = historyRepository,
                    session = session,
                    scheduledTaskSession = scheduledTaskSessionFor(target),
                    scope = scope,
                    failureMessages = failureMessages,
                    onUserMessageAdded = onUserMessageAdded,
                    followBottom = followBottom,
                )
            }
        }
        is GoalCommand.Edit -> {
            if (command.objective.isBlank()) {
                appendFeedback(goalMessages.objectiveRequired, isError = true)
            } else if (target.goal == null) {
                appendFeedback(goalMessages.noGoal, isError = true)
            } else {
                target.runningJob?.cancel()
                startGoalRun(
                    target = target,
                    userMessage = userMessage,
                    mutateGoal = { session.editGoalFromUser(command.objective) },
                    configuration = configuration,
                    service = service,
                    generationRunner = generationRunner,
                    historyRepository = historyRepository,
                    session = session,
                    scheduledTaskSession = scheduledTaskSessionFor(target),
                    scope = scope,
                    failureMessages = failureMessages,
                    onUserMessageAdded = onUserMessageAdded,
                    followBottom = followBottom,
                )
            }
        }
    }
}

private fun startGoalRun(
    target: ConversationState,
    userMessage: ChatMessage,
    mutateGoal: suspend () -> ThreadGoal,
    configuration: ModelConfiguration?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    session: GoalSession,
    scheduledTaskSession: ScheduledTaskSession?,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
    followBottom: (ConversationState) -> Unit,
) {
    target.shouldResumeGoal = false
    scope.launch {
        target.runningJob?.join()
        val goal = runCatching { mutateGoal() }.getOrElse { error ->
            val failed = ChatMessage(userMessage.id + 1L, MessageRole.Assistant, error.message.orEmpty(), true)
            target.messages += userMessage
            target.messages += failed
            onUserMessageAdded(target, userMessage)
            persistMessage(target, userMessage, historyRepository)
            persistMessage(target, failed, historyRepository)
            return@launch
        }
        if (configuration == null) {
            appendSetupRequiredMessages(
                target,
                userMessage,
                failureMessages.setupModel,
                historyRepository,
                scope,
                onUserMessageAdded,
            )
            return@launch
        }
        val history = target.messages.toList()
        target.messages += userMessage
        onUserMessageAdded(target, userMessage)
        val assistantId = userMessage.id + 1L
        prepareStreamingResponse(target, assistantId)
        launchStreamingResponse(
            target = target,
            assistantId = assistantId,
            configuration = configuration,
            history = history,
            prompt = goalContinuationPrompt(goal),
            service = service,
            generationRunner = generationRunner,
            historyRepository = historyRepository,
            goalSession = session,
            scheduledTaskSession = scheduledTaskSession,
            failureMessages = failureMessages,
            followBottom = followBottom,
            beforeRequest = { persistMessage(target, userMessage, historyRepository) },
        )
    }
}

internal fun continueRestoredGoal(
    target: ConversationState,
    configuration: ModelConfiguration?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    failureMessages: ChatFailureMessages,
    scheduledTaskSession: ScheduledTaskSession? = null,
    followBottom: (ConversationState) -> Unit,
) {
    val goal = target.goal ?: return
    val activeConfiguration = configuration ?: return
    if (!target.shouldResumeGoal || target.isGenerating || goal.status != ThreadGoalStatus.Active) return
    target.shouldResumeGoal = false
    val assistantId = nextMessageId(target)
    prepareStreamingResponse(target, assistantId)
    launchStreamingResponse(
        target = target,
        assistantId = assistantId,
        configuration = activeConfiguration,
        history = target.messages.dropLast(1),
        prompt = goalContinuationPrompt(goal),
        service = service,
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        goalSession = ConversationGoalSession(target, historyRepository),
        scheduledTaskSession = scheduledTaskSession,
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = {},
    )
}

private suspend fun persistMessage(
    target: ConversationState,
    message: ChatMessage,
    historyRepository: ConversationHistoryRepository,
) {
    historyRepository.appendMessage(
        conversationId = target.id,
        title = target.title,
        messageId = message.id,
        role = message.role.name,
        content = if (message.toolUses.isEmpty() && message.subAgents.isEmpty()) message.content else message.toStoredContent(),
        isError = message.isError,
    )
}

internal suspend fun runScheduledTask(
    target: ConversationState,
    task: ScheduledTask,
    configuration: ModelConfiguration?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    scheduledTaskSession: ScheduledTaskSession,
    scheduledTaskCompletionSession: ScheduledTaskCompletionSession,
    failureMessages: ChatFailureMessages,
    followBottom: (ConversationState) -> Unit = {},
    onResponseFinished: suspend (completed: Boolean) -> Unit = {},
): Boolean {
    val activeConfiguration = configuration ?: return false
    if (target.isGenerating) return false
    val history = target.messages.toList()
    val userMessage = ChatMessage(
        id = nextMessageId(target),
        role = MessageRole.User,
        content = task.prompt,
    )
    persistMessage(target, userMessage, historyRepository)
    target.messages += userMessage
    val assistantId = userMessage.id + 1L
    prepareStreamingResponse(target, assistantId)
    followBottom(target)
    launchStreamingResponse(
        target = target,
        assistantId = assistantId,
        configuration = activeConfiguration,
        history = history,
        prompt = scheduledTaskExecutionPrompt(task.prompt),
        service = service,
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        goalSession = ConversationGoalSession(target, historyRepository),
        scheduledTaskSession = scheduledTaskSession,
        scheduledTaskCompletionSession = scheduledTaskCompletionSession,
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = {},
        onResponseFinished = onResponseFinished,
    )
    return true
}

internal fun regenerateMessage(
    answer: ChatMessage,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    scheduledTaskSession: ScheduledTaskSession? = null,
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
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        goalSession = ConversationGoalSession(target, historyRepository),
        scheduledTaskSession = scheduledTaskSession,
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
    onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
) {
    val assistantMessage = ChatMessage(
        id = userMessage.id + 1L,
        role = MessageRole.Assistant,
        content = setupMessage,
    )
    target.messages += userMessage
    target.messages += assistantMessage
    onUserMessageAdded(target, userMessage)
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
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    goalSession: GoalSession,
    scheduledTaskSession: ScheduledTaskSession? = null,
    scheduledTaskCompletionSession: ScheduledTaskCompletionSession? = null,
    failureMessages: ChatFailureMessages,
    followBottom: (ConversationState) -> Unit,
    beforeRequest: suspend () -> Unit,
    onResponseFinished: suspend (completed: Boolean) -> Unit = {},
) {
    target.runningJob = generationRunner.launch {
        runCatching { beforeRequest() }
        var responseFinished = false
        try {
            val answer = service.replyStreaming(
                configuration = configuration,
                history = history,
                prompt = prompt,
                goalSession = goalSession,
                scheduledTaskSession = scheduledTaskSession,
                scheduledTaskCompletionSession = scheduledTaskCompletionSession,
                onToolUse = { event ->
                    target.isAwaitingFirstToken = false
                    target.applyToolUseEvent(assistantId, event)
                    followBottom(target)
                },
                onSubAgent = { event ->
                    target.isAwaitingFirstToken = false
                    target.applySubAgentEvent(assistantId, event)
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
            responseFinished = true
        } catch (cancelled: CancellationException) {
            target.persistOrRemovePartialMessage(assistantId, historyRepository)
            runCatching {
                if (goalSession.getGoal()?.status == ThreadGoalStatus.Active) {
                    goalSession.setStatusFromUser(ThreadGoalStatus.Paused)
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            target.persistOrRemovePartialMessage(assistantId, historyRepository)
            runCatching {
                if (goalSession.getGoal()?.status == ThreadGoalStatus.Active) {
                    goalSession.setStatusFromUser(ThreadGoalStatus.Blocked)
                }
            }
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
            responseFinished = true
        } finally {
            target.isGenerating = false
            target.isAwaitingFirstToken = false
            target.runningJob = null
            runCatching { onResponseFinished(responseFinished) }
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
            is ToolUseEvent.Updated -> message.copy(
                toolUses = message.toolUses.map { toolUse ->
                    if (toolUse.id != event.id) toolUse else toolUse.copy(input = event.input)
                },
            )
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

internal fun ConversationState.applySubAgentEvent(messageId: Long, event: SubAgentEvent) {
    updateMessage(messageId) { message ->
        when (event) {
            is SubAgentEvent.Spawned -> message.copy(
                subAgents = message.subAgents.filterNot { it.path == event.path } + SubAgentInfo(
                    path = event.path,
                    parentPath = event.parentPath,
                    taskName = event.taskName,
                    prompt = event.prompt,
                    textOffset = message.content.length,
                ),
            )
            is SubAgentEvent.StatusChanged -> message.copy(
                subAgents = message.subAgents.map { agent ->
                    if (agent.path != event.path) agent else agent.copy(
                        status = event.status.toRunStatus(),
                        currentTool = event.currentTool,
                        output = event.output ?: agent.output,
                    )
                },
            )
        }
    }
}

private fun SubAgentStatus.toRunStatus(): SubAgentRunStatus = when (this) {
    SubAgentStatus.Pending -> SubAgentRunStatus.Pending
    SubAgentStatus.Running -> SubAgentRunStatus.Running
    SubAgentStatus.Waiting -> SubAgentRunStatus.Waiting
    SubAgentStatus.Completed -> SubAgentRunStatus.Completed
    SubAgentStatus.Failed -> SubAgentRunStatus.Failed
    SubAgentStatus.Interrupted -> SubAgentRunStatus.Interrupted
}

private suspend fun ConversationState.persistOrRemovePartialMessage(
    messageId: Long,
    historyRepository: ConversationHistoryRepository,
) = withContext(NonCancellable) {
    val partial = messages.firstOrNull { it.id == messageId }
    if (partial == null || (partial.content.isBlank() && partial.toolUses.isEmpty() && partial.subAgents.isEmpty())) {
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
