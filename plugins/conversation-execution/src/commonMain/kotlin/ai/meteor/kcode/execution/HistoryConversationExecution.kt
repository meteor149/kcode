package ai.meteor.kcode.execution

import ai.meteor.kcode.plugin.api.ExecutionAdmission
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ConversationCommandOperations
import ai.meteor.kcode.chat.ConversationCommandRequest
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.chat.ConversationResponseRequest
import ai.meteor.kcode.chat.ConversationResponseLifecycle
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.HistoryMessageWrite
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.model.SubAgentRunStatus
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun executeSendMessage(
    prompt: String,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    onSendToNew: (String) -> ConversationState,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    messageCodec: ChatMessageCodec,
    goalSessionFactory: GoalSessionFactory,
    scope: CoroutineScope,
    failureMessages: ChatFailureMessages,
    language: AppLanguage,
    commands: ConversationCommandSnapshot,
    scheduledTaskSessionFor: (ConversationState) -> ScheduledTaskSession? = { null },
    onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
    followBottom: (ConversationState) -> Unit,
) {
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isEmpty()) return
    conversation?.executionFailure = null
    val command = commands.resolve(cleanPrompt)
    if (command != null) {
        if (conversation?.isGenerating == true && !command.allowedDuringGeneration) return
        run {
            var commandTarget = conversation
            var requestOpen = true
            fun requireRequestOpen() = check(requestOpen) { "Command execution request is no longer active" }
            val operations = object : ConversationCommandOperations {
                override fun nextMessageId(target: ConversationState): Long {
                    requireRequestOpen()
                    return target.reserveMessageIds(2)
                }
                override suspend fun appendFeedback(target: ConversationState, user: ChatMessage, content: String, isError: Boolean) {
                    requireRequestOpen()
                    val safeContent = configuration?.apiKey?.takeIf { it.isNotEmpty() }
                        ?.let { content.replace(it, "••••") } ?: content
                    val assistant = ChatMessage(user.id + 1L, MessageRole.Assistant, safeContent, isError)
                    historyRepository.appendMessages(target.id, target.title, listOf(user, assistant).map {
                        HistoryMessageWrite(it.id, it.role.name, messageCodec.encode(it), it.isError)
                    })
                    requireRequestOpen()
                    currentCoroutineContext().ensureActive()
                    target.messages += user
                    target.messages += assistant
                    onUserMessageAdded(target, user)
                    followBottom(target)
                }
                override suspend fun startResponse(target: ConversationState, user: ChatMessage, prompt: String, goalSession: GoalSession?) {
                    requireRequestOpen()
                    target.shouldResumeGoal = false
                    if (configuration == null) {
                        appendFeedback(target, user, failureMessages.setupModel, false)
                        return
                    }
                    val history = target.messages.toList()
                    persistMessage(target, user, historyRepository, messageCodec)
                    requireRequestOpen()
                    currentCoroutineContext().ensureActive()
                    target.messages += user
                    onUserMessageAdded(target, user)
                    val assistantId = user.id + 1L
                    prepareStreamingResponse(target, assistantId)
                    launchStreamingResponse(
                        target = target, assistantId = assistantId, configuration = configuration,
                        history = history, prompt = prompt, service = service, generationRunner = generationRunner,
                        historyRepository = historyRepository,
                        messageCodec = messageCodec, goalSession = goalSession,
                        scheduledTaskSession = scheduledTaskSessionFor(target), failureMessages = failureMessages,
                        followBottom = followBottom, beforeRequest = {},
                    )
                }
            }
            try {
                command.execute(ConversationCommandRequest(cleanPrompt, language, conversation,
                    { seed -> requireRequestOpen(); onSendToNew(seed).also { commandTarget = it } }, operations))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = error.message ?: failureMessages.connectionFailed
                commandTarget?.executionFailure = configuration?.apiKey?.takeIf { it.isNotEmpty() }
                    ?.let { detail.replace(it, "••••") } ?: detail
            } finally {
                requestOpen = false
            }
        }
        return
    }
    val target = conversation ?: onSendToNew(cleanPrompt)
    if (target.isGenerating) return
    val userMessage = ChatMessage(
        id = nextMessageId(target, count = 2),
        role = MessageRole.User,
        content = cleanPrompt,
    )
    if (configuration == null) {
        appendSetupRequiredMessages(
            target = target,
            userMessage = userMessage,
            setupMessage = failureMessages.setupModel,
            historyRepository = historyRepository,
            messageCodec = messageCodec,
            scope = scope,
            onUserMessageAdded = onUserMessageAdded,
        )
        return
    }

    val history = target.messages.toList()
    val assistantId = userMessage.id + 1L
    target.isGenerating = true
    target.isAwaitingFirstToken = true
    launchStreamingResponse(
        target = target,
        assistantId = assistantId,
        configuration = configuration,
        history = history,
        prompt = cleanPrompt,
        service = service,
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        messageCodec = messageCodec,
        goalSession = goalSessionFactory.create(target),
        scheduledTaskSession = scheduledTaskSessionFor(target),
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = {
            historyRepository.appendMessage(
                conversationId = target.id,
                title = target.title,
                messageId = userMessage.id,
                role = userMessage.role.name,
                content = messageCodec.encode(userMessage),
            )
            currentCoroutineContext().ensureActive()
            target.messages += userMessage
            prepareStreamingResponse(target, assistantId)
            onUserMessageAdded(target, userMessage)
        },
    )
}

private suspend fun persistMessage(
    target: ConversationState,
    message: ChatMessage,
    historyRepository: ConversationHistoryRepository,
    messageCodec: ChatMessageCodec,
) {
    historyRepository.appendMessage(
        conversationId = target.id,
        title = target.title,
        messageId = message.id,
        role = message.role.name,
        content = messageCodec.encode(message),
        isError = message.isError,
    )
}

private suspend fun executeStartResponse(
    target: ConversationState,
    request: ConversationResponseRequest,
    configuration: ModelConfiguration?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    messageCodec: ChatMessageCodec,
    failureMessages: ChatFailureMessages,
    followBottom: (ConversationState) -> Unit,
    onResponseFinished: suspend (completed: Boolean) -> Unit,
): Boolean {
    val activeConfiguration = configuration ?: return false
    if (target.isGenerating) return false
    target.executionFailure = null
    val history = target.messages.toList()
    val userMessage = request.userMessage?.let {
        ChatMessage(nextMessageId(target, count = 2), MessageRole.User, it)
    }
    val assistantId = userMessage?.let { it.id + 1L } ?: nextMessageId(target)
    target.isGenerating = true
    target.isAwaitingFirstToken = true
    val job = launchStreamingResponse(
        target = target,
        assistantId = assistantId,
        configuration = activeConfiguration,
        history = history,
        prompt = request.prompt,
        service = service,
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        messageCodec = messageCodec,
        goalSession = request.goalSession,
        scheduledTaskSession = request.scheduledTaskSession,
        scheduledTaskCompletionSession = request.scheduledTaskCompletionSession,
        lifecycle = request.lifecycle,
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = {
            if (userMessage != null) {
                persistMessage(target, userMessage, historyRepository, messageCodec)
                currentCoroutineContext().ensureActive()
                target.messages += userMessage
            }
            prepareStreamingResponse(target, assistantId)
            followBottom(target)
        },
        onResponseFinished = onResponseFinished,
    )
    currentCoroutineContext().ensureActive()
    if (job.isCancelled) return false
    return true
}

private suspend fun executeRegenerateMessage(
    answer: ChatMessage,
    configuration: ModelConfiguration?,
    conversation: ConversationState?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    messageCodec: ChatMessageCodec,
    goalSessionFactory: GoalSessionFactory,
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
    target.executionFailure = null
    val replacementId = nextMessageId(target)
    target.isGenerating = true
    target.isAwaitingFirstToken = true
    launchStreamingResponse(
        target = target,
        assistantId = replacementId,
        configuration = activeConfiguration,
        history = history,
        prompt = prompt,
        service = service,
        generationRunner = generationRunner,
        historyRepository = historyRepository,
        messageCodec = messageCodec,
        goalSession = goalSessionFactory.create(target),
        scheduledTaskSession = scheduledTaskSession,
        failureMessages = failureMessages,
        followBottom = followBottom,
        beforeRequest = {
            historyRepository.deleteMessagesFrom(target.id, answer.id)
            currentCoroutineContext().ensureActive()
            target.messages.subList(answerIndex, target.messages.size).clear()
            prepareStreamingResponse(target, replacementId)
            onFollowLatestChange(shouldFollowLatest)
            followBottom(target)
        },
    )
}

private suspend fun appendSetupRequiredMessages(
    target: ConversationState,
    userMessage: ChatMessage,
    setupMessage: String,
    historyRepository: ConversationHistoryRepository,
    messageCodec: ChatMessageCodec,
    scope: CoroutineScope,
    onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
) {
    val assistantMessage = ChatMessage(
        id = userMessage.id + 1L,
        role = MessageRole.Assistant,
        content = setupMessage,
    )
    target.isGenerating = true
    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            historyRepository.appendMessages(target.id, target.title, listOf(userMessage, assistantMessage).map {
                HistoryMessageWrite(it.id, it.role.name, messageCodec.encode(it), it.isError)
            })
            currentCoroutineContext().ensureActive()
            target.messages += userMessage
            target.messages += assistantMessage
            onUserMessageAdded(target, userMessage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            target.executionFailure = error.message ?: setupMessage
        } finally {
            target.isGenerating = false
            target.runningJob = null
        }
    }
    target.runningJob = job
    job.invokeOnCompletion {
        if (target.runningJob === job) {
            target.isGenerating = false
            target.runningJob = null
        }
    }
    job.start()
    job.awaitOwned()
}

private fun prepareStreamingResponse(target: ConversationState, assistantId: Long) {
    target.isGenerating = true
    target.isAwaitingFirstToken = true
    target.messages += ChatMessage(assistantId, MessageRole.Assistant, "")
}

private suspend fun launchStreamingResponse(
    target: ConversationState,
    assistantId: Long,
    configuration: ModelConfiguration,
    history: List<ChatMessage>,
    prompt: String,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    historyRepository: ConversationHistoryRepository,
    messageCodec: ChatMessageCodec,
    goalSession: GoalSession?,
    lifecycle: ConversationResponseLifecycle? = goalSession,
    scheduledTaskSession: ScheduledTaskSession? = null,
    scheduledTaskCompletionSession: ScheduledTaskCompletionSession? = null,
    failureMessages: ChatFailureMessages,
    followBottom: (ConversationState) -> Unit,
    beforeRequest: suspend () -> Unit,
    onResponseFinished: suspend (completed: Boolean) -> Unit = {},
): Job {
    var started = false
    val response: suspend CoroutineScope.() -> Unit = {
        started = true
        var responseFinished = false
        var requestPrepared = false
        var modelFinished = false
        try {
            beforeRequest()
            requestPrepared = true
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
            modelFinished = true
            target.isAwaitingFirstToken = false
            target.updateMessage(assistantId) {
                if (it.content.isBlank() && answer.isNotBlank()) it.copy(content = answer) else it
            }
            val assistantMessage = target.messages.first { it.id == assistantId }
            historyRepository.appendMessage(
                conversationId = target.id,
                title = target.title,
                messageId = assistantMessage.id,
                role = assistantMessage.role.name,
                content = messageCodec.encode(assistantMessage),
            )
            responseFinished = true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                target.persistOrRemovePartialMessage(assistantId, historyRepository, messageCodec, configuration, failureMessages)
                try {
                    lifecycle?.onCancelled()
                } catch (error: Throwable) {
                    target.recordFailure(error, configuration, failureMessages)
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            if (!requestPrepared || modelFinished) {
                target.messages.removeAll { it.id == assistantId }
                target.recordFailure(error, configuration, failureMessages)
            } else {
                val partialSaved = target.persistOrRemovePartialMessage(
                    assistantId, historyRepository, messageCodec, configuration, failureMessages,
                )
                try {
                    lifecycle?.onFailed()
                    val errorMessage = ChatMessage(
                        id = nextMessageId(target),
                        role = MessageRole.Assistant,
                        content = safeFailureDetail(error, configuration, failureMessages),
                        isError = true,
                    )
                    persistMessage(target, errorMessage, historyRepository, messageCodec)
                    currentCoroutineContext().ensureActive()
                    target.messages += errorMessage
                    followBottom(target)
                    responseFinished = partialSaved
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    target.recordFailure(failure, configuration, failureMessages)
                }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        onResponseFinished(responseFinished)
                    } catch (error: Throwable) {
                        target.recordFailure(error, configuration, failureMessages)
                    }
                }
            } finally {
                target.isGenerating = false
                target.isAwaitingFirstToken = false
                target.runningJob = null
            }
        }
    }
    val job = try {
        generationRunner.launch(response)
    } catch (error: Throwable) {
        // A withdrawn runner can reject synchronously, before the body owns cleanup.
        if (started) throw error
        try {
            if (error !is CancellationException) target.recordFailure(error, configuration, failureMessages)
            withContext(NonCancellable) {
                try {
                    onResponseFinished(false)
                } catch (failure: Throwable) {
                    target.recordFailure(failure, configuration, failureMessages)
                }
            }
        } finally {
            target.messages.removeAll { it.id == assistantId && it.role == MessageRole.Assistant && it.content.isEmpty() }
            target.isGenerating = false
            target.isAwaitingFirstToken = false
            target.runningJob = null
        }
        if (error is CancellationException) throw error
        return Job().apply { cancel() }
    }
    target.runningJob = job.takeUnless { it.isCompleted }
    job.invokeOnCompletion {
        // Admission or runner cancellation can reject the body before its finally starts.
        if (!started && (target.runningJob === job || target.runningJob == null)) {
            target.messages.removeAll { it.id == assistantId && it.role == MessageRole.Assistant && it.content.isEmpty() }
            target.isGenerating = false
            target.isAwaitingFirstToken = false
            target.runningJob = null
        }
    }
    return job
}

/** Setup feedback stays owned by its admitted request through cancellation cleanup. */
private suspend fun Job.awaitOwned() {
    try { join() }
    finally { withContext(NonCancellable) { if (!isCompleted) cancelAndJoin() } }
}

private fun safeFailureDetail(
    error: Throwable,
    configuration: ModelConfiguration,
    failureMessages: ChatFailureMessages,
): String {
    val detail = error.message ?: failureMessages.connectionFailed
    return configuration.apiKey.takeIf { it.isNotEmpty() }?.let { detail.replace(it, "••••") } ?: detail
}

private fun ConversationState.recordFailure(
    error: Throwable,
    configuration: ModelConfiguration,
    failureMessages: ChatFailureMessages,
) {
    executionFailure = safeFailureDetail(error, configuration, failureMessages)
}

private fun nextMessageId(conversation: ConversationState, count: Int = 1): Long =
    conversation.reserveMessageIds(count)

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
    messageCodec: ChatMessageCodec,
    configuration: ModelConfiguration,
    failureMessages: ChatFailureMessages,
): Boolean = withContext(NonCancellable) {
    val partial = messages.firstOrNull { it.id == messageId }
    if (partial == null || (partial.content.isBlank() && partial.toolUses.isEmpty() && partial.subAgents.isEmpty())) {
        messages.removeAll { it.id == messageId }
        return@withContext true
    }
    try {
        persistMessage(this@persistOrRemovePartialMessage, partial, historyRepository, messageCodec)
        true
    } catch (error: Throwable) {
        messages.removeAll { it.id == messageId }
        recordFailure(error, configuration, failureMessages)
        false
    }
}

internal class HistoryConversationExecution(
    private val historyRepository: ConversationHistoryRepository,
    private val messageCodec: ChatMessageCodec,
    private val admission: ExecutionAdmission? = null,
    private val commandSnapshot: () -> ConversationCommandSnapshot = { ConversationCommandSnapshot() },
) : ConversationExecution {
    private var closed = false
    private val scopes = mutableMapOf<CoroutineScope, CoroutineScope>()
    private val conversations = mutableSetOf<ConversationState>()
    private fun requireOpen() = check(!closed) { "Conversation execution provider has been disposed" }
    private fun ownedScope(parent: CoroutineScope): CoroutineScope = scopes.getOrPut(parent) {
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    private suspend fun <T> admitted(block: suspend () -> T): T =
        if (admission == null) block() else admission.run(block)

    private fun coordinated(runner: ChatGenerationRunner): ChatGenerationRunner =
        if (admission == null) runner else object : ChatGenerationRunner by runner {
            override fun launch(block: suspend CoroutineScope.() -> Unit): Job = runner.launch {
                admitted { block() }
            }
        }

    private fun track(conversation: ConversationState?) { if (conversation != null) conversations += conversation }
    suspend fun close() {
        closed = true
        val running = conversations.mapNotNull { it.runningJob }.distinct()
        running.forEach { it.cancel() }
        scopes.values.forEach { it.coroutineContext[Job]?.cancel() }
        running.forEach { it.join() }
        scopes.values.forEach { it.coroutineContext[Job]?.join() }
        conversations.clear()
        scopes.clear()
    }

    override fun sendMessage(
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
        scheduledTaskSessionFor: (ConversationState) -> ScheduledTaskSession?,
        onUserMessageAdded: (ConversationState, ChatMessage) -> Unit,
        followBottom: (ConversationState) -> Unit,
    ) {
        requireOpen()
        val requestScope = ownedScope(scope)
        requestScope.launch(start = CoroutineStart.UNDISPATCHED) {
            admitted {
                requireOpen()
                track(conversation)
                executeSendMessage(
                    prompt = prompt,
                    configuration = configuration,
                    conversation = conversation,
                    onSendToNew = { prompt -> onSendToNew(prompt).also(::track) },
                    service = service,
                    generationRunner = coordinated(generationRunner),
                    goalSessionFactory = goalSessionFactory,
                    scope = requestScope,
                    failureMessages = failureMessages,
                    language = language,
                    commands = commandSnapshot(),
                    scheduledTaskSessionFor = scheduledTaskSessionFor,
                    onUserMessageAdded = onUserMessageAdded,
                    followBottom = followBottom,
                    historyRepository = historyRepository,
                    messageCodec = messageCodec,
                )
            }
        }
    }

    override suspend fun startResponse(
        target: ConversationState,
        request: ConversationResponseRequest,
        configuration: ModelConfiguration?,
        service: ChatService,
        generationRunner: ChatGenerationRunner,
        failureMessages: ChatFailureMessages,
        followBottom: (ConversationState) -> Unit,
        onResponseFinished: suspend (completed: Boolean) -> Unit,
    ): Boolean {
        requireOpen()
        return admitted {
            requireOpen()
            currentCoroutineContext().ensureActive()
            track(target)
            executeStartResponse(target, request, configuration, service, coordinated(generationRunner),
                historyRepository, messageCodec, failureMessages, followBottom, onResponseFinished)
        }
    }

    override fun regenerateMessage(
        answer: ChatMessage,
        configuration: ModelConfiguration?,
        conversation: ConversationState?,
        service: ChatService,
        generationRunner: ChatGenerationRunner,
        goalSessionFactory: GoalSessionFactory,
        scope: CoroutineScope,
        failureMessages: ChatFailureMessages,
        scheduledTaskSession: ScheduledTaskSession?,
        shouldFollowLatest: Boolean,
        onFollowLatestChange: (Boolean) -> Unit,
        followBottom: (ConversationState) -> Unit,
    ) {
        requireOpen()
        val requestScope = ownedScope(scope)
        requestScope.launch(start = CoroutineStart.UNDISPATCHED) {
            admitted {
                requireOpen()
                track(conversation)
                executeRegenerateMessage(
                    answer = answer,
                    configuration = configuration,
                    conversation = conversation,
                    service = service,
                    generationRunner = coordinated(generationRunner),
                    goalSessionFactory = goalSessionFactory,
                    scope = requestScope,
                    failureMessages = failureMessages,
                    scheduledTaskSession = scheduledTaskSession,
                    shouldFollowLatest = shouldFollowLatest,
                    onFollowLatestChange = onFollowLatestChange,
                    followBottom = followBottom,
                    historyRepository = historyRepository,
                    messageCodec = messageCodec,
                )
            }
        }
    }
}
