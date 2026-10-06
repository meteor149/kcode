package ai.meteor.kcode.execution

import ai.meteor.kcode.plugin.api.ExecutionAdmission
import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.plugin.scheduledispatch.PersistedScheduledTaskCompletionSession
import ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ConversationResponseLifecycle
import ai.meteor.kcode.chat.ConversationResponseRequest
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ConversationCommand
import ai.meteor.kcode.chat.ConversationCommandContribution
import ai.meteor.kcode.chat.ConversationCommandOperations
import ai.meteor.kcode.chat.ConversationCommandRequest
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.HistoryMessageWrite
import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.messagecodec.EnvelopeChatMessageCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ConversationExecutionLifecycleTest {
    private val configuration = ModelConfiguration(ModelProvider.DeepSeek, "test", "test-key", temperature = 0.6)
    private val failures = ChatFailureMessages("setup", "connection")
    private val language = AppLanguage.English

    @Test
    fun closedAdmissionRejectsCommandsAllocationSetupResponseAndRegenerationBeforeSideEffects() = runTest {
        var writes = 0
        var deletes = 0
        var allocations = 0
        var snapshots = 0
        var callbacks = 0
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(conversationId: Long, title: String, messageId: Long,
                role: String, content: String, isError: Boolean) { writes++ }
            override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) { writes++ }
            override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) { deletes++ }
        }
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T = throw CancellationException("Closed")
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec(), admission) {
            snapshots++
            ConversationCommandSnapshot()
        }
        val conversation = HistoryConversationState(1, "test")
        val user = ChatMessage(1, MessageRole.User, "hello")
        val answer = ChatMessage(2, MessageRole.Assistant, "saved")
        conversation.messages.addAll(listOf(user, answer))
        conversation.executionFailure = "previous"
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("Denied model")
        }
        val runner = OwnedChatGenerationRunner(scope = backgroundScope)
        fun send(prompt: String, configured: Boolean, target: Boolean) = execution.sendMessage(
            prompt, configuration.takeIf { configured }, conversation.takeIf { target },
            { allocations++; HistoryConversationState(2, "new") }, service, runner, UnavailableGoalSessions,
            backgroundScope, failures, language, onUserMessageAdded = { _, _ -> callbacks++ }, followBottom = { callbacks++ },
        )
        send("/custom", true, true)
        send("hello", true, false)
        send("setup", false, true)
        execution.regenerateMessage(answer, configuration, conversation, service, runner, UnavailableGoalSessions,
            backgroundScope, failures, shouldFollowLatest = true, onFollowLatestChange = { callbacks++ }, followBottom = { callbacks++ })
        assertFailsWith<CancellationException> {
            execution.startResponse(conversation, ConversationResponseRequest("scheduled", userMessage = "input"),
                configuration, service, runner, failures, onResponseFinished = { callbacks++ })
        }
        testScheduler.runCurrent()
        assertEquals(0, writes)
        assertEquals(0, deletes)
        assertEquals(0, allocations)
        assertEquals(0, snapshots)
        assertEquals(0, callbacks)
        assertEquals(0, runner.activeTasks.value)
        assertEquals(listOf(user, answer), conversation.messages.toList())
        assertEquals("previous", conversation.executionFailure)
        assertFalse(conversation.isGenerating)
        assertFalse(conversation.isAwaitingFirstToken)
        assertEquals(null, conversation.runningJob)
        assertEquals(3L, conversation.reserveMessageIds())
        execution.close()
    }

    @Test
    fun admittedCommandCancellationJoinsHistoryCleanupWithoutStartingGeneration() = runTest {
        val jobs = mutableSetOf<Job>()
        var open = true
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (!open) throw CancellationException("Closed")
                val job = checkNotNull(currentCoroutineContext()[Job])
                jobs += job
                try { return block() } finally { jobs -= job }
            }
        }
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
        }
        val command = ConversationCommandContribution("custom") { input ->
            if (input != "/custom") null else object : ConversationCommand {
                override val allowedDuringGeneration = true
                override suspend fun execute(request: ConversationCommandRequest) {
                    val target = checkNotNull(request.conversation)
                    request.operations.appendFeedback(target,
                        ChatMessage(request.operations.nextMessageId(target), MessageRole.User, request.prompt), "feedback")
                }
            }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec(), admission) {
            ConversationCommandSnapshot(listOf(command))
        }
        val conversation = HistoryConversationState(1, "test")
        val runner = OwnedChatGenerationRunner(scope = backgroundScope)
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("No model")
        }
        execution.sendMessage("/custom", configuration, conversation, { error("No allocation") }, service,
            runner, UnavailableGoalSessions, backgroundScope, failures, language,
            onUserMessageAdded = { _, _ -> error("No publication") }, followBottom = {})
        entered.await()
        assertEquals(1, jobs.size)
        assertEquals(0, runner.activeTasks.value)
        val cancellation = async {
            open = false
            val active = jobs.toList()
            active.forEach { it.cancel() }
            active.forEach { it.join() }
        }
        cleanup.await()
        assertFalse(cancellation.isCompleted)
        assertTrue(conversation.messages.isEmpty())
        release.complete(Unit)
        cancellation.await()
        assertTrue(jobs.isEmpty())
        execution.close()
    }

    @Test
    fun requestScopeWithdrawalDoesNotCancelAnAdmittedBackgroundResponse() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        val execution = HistoryConversationExecution(EmptyHistoryFixture(), EnvelopeChatMessageCodec(), admission)
        val conversation = HistoryConversationState(1, "test")
        val pageJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val pageScope = CoroutineScope(backgroundScope.coroutineContext + pageJob)
        val runner = OwnedChatGenerationRunner(scope = backgroundScope)
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        execution.sendMessage("hello", configuration, conversation, { error("No allocation") }, service,
            runner, UnavailableGoalSessions, pageScope, failures, language,
            onUserMessageAdded = { _, _ -> }, followBottom = {})
        entered.await()
        pageJob.cancelAndJoin()
        assertFalse(stopped.isCompleted)
        assertTrue(conversation.isGenerating)
        assertEquals(1, runner.activeTasks.value)
        execution.close()
        assertTrue(stopped.isCompleted)
        assertFalse(conversation.isGenerating)
    }

    @Test
    fun synchronousRunnerCancellationKeepsCompletionCleanupAdmittedBeforeClearingFlags() = runTest {
        var admitted = 0
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                admitted++
                try { return block() } finally { admitted-- }
            }
        }
        val execution = HistoryConversationExecution(EmptyHistoryFixture(), EnvelopeChatMessageCodec(), admission)
        val conversation = HistoryConversationState(1, "test")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = object : ChatGenerationRunner by OwnedChatGenerationRunner(scope = backgroundScope) {
            override fun launch(block: suspend CoroutineScope.() -> Unit): Job = throw CancellationException("Withdrawn")
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("Denied model")
        }
        val request = async {
            execution.startResponse(conversation, ConversationResponseRequest("scheduled"), configuration,
                service, runner, failures, onResponseFinished = {
                    assertFalse(it)
                    entered.complete(Unit)
                    release.await()
                })
        }
        entered.await()
        assertEquals(1, admitted)
        assertTrue(conversation.isGenerating)
        request.cancel()
        testScheduler.runCurrent()
        assertFalse(request.isCompleted)
        assertEquals(1, admitted)
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        request.join()
        assertEquals(0, admitted)
        assertFalse(conversation.isGenerating)
        assertFalse(conversation.isAwaitingFirstToken)
        assertEquals(null, conversation.executionFailure)
        assertEquals(null, conversation.runningJob)
        execution.close()
    }

    @Test
    fun closedRunnerRejectsResponseWithoutLeavingGeneratingStateAndAllowsRetry() = runTest {
        var writes = 0
        var completions = 0
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(conversationId: Long, title: String, messageId: Long,
                role: String, content: String, isError: Boolean) { writes++ }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        val saved = ChatMessage(1, MessageRole.User, "saved")
        conversation.messages += saved
        val retiredScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        retiredScope.coroutineContext[Job]!!.cancelAndJoin()
        val retiredRunner = OwnedChatGenerationRunner(scope = retiredScope)
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = "answer"
        }
        assertFalse(execution.startResponse(conversation, ConversationResponseRequest("scheduled", userMessage = "new"),
            configuration, service, retiredRunner, failures, onResponseFinished = {
                assertFalse(it)
                completions++
            }))
        assertEquals(1, completions)
        assertEquals(0, writes)
        assertEquals(listOf(saved), conversation.messages.toList())
        assertFalse(conversation.isGenerating)
        assertFalse(conversation.isAwaitingFirstToken)
        assertEquals(null, conversation.runningJob)
        assertEquals("Generation runner is closed", conversation.executionFailure)
        val runner = OwnedChatGenerationRunner(scope = backgroundScope)
        assertTrue(execution.startResponse(conversation, ConversationResponseRequest("retry"),
            configuration, service, runner, failures))
        testScheduler.runCurrent()
        assertEquals("answer", conversation.messages.last().content)
        assertEquals(null, conversation.executionFailure)
        execution.close()
    }

    @Test
    fun closedRunnerDoesNotLeaveSendOrRegenerationStuckOrChangeSavedHistory() = runTest {
        var writes = 0
        var deletes = 0
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(conversationId: Long, title: String, messageId: Long,
                role: String, content: String, isError: Boolean) { writes++ }
            override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) { deletes++ }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val retiredScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        retiredScope.coroutineContext[Job]!!.cancelAndJoin()
        val runner = OwnedChatGenerationRunner(scope = retiredScope)
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("Retired runner must not execute")
        }
        for (regenerate in listOf(false, true)) {
            val conversation = HistoryConversationState(1, "test")
            val user = ChatMessage(1, MessageRole.User, "saved")
            val answer = ChatMessage(2, MessageRole.Assistant, "answer")
            conversation.messages.addAll(listOf(user, answer))
            if (regenerate) {
                execution.regenerateMessage(answer, configuration, conversation, service, runner,
                    UnavailableGoalSessions, backgroundScope, failures, shouldFollowLatest = true,
                    onFollowLatestChange = {}, followBottom = {})
            } else {
                execution.sendMessage("new", configuration, conversation, { error("Existing conversation") },
                    service, runner, UnavailableGoalSessions, backgroundScope, failures, language,
                    onUserMessageAdded = { _, _ -> }, followBottom = {})
            }
            testScheduler.runCurrent()
            assertEquals(listOf(user, answer), conversation.messages.toList())
            assertFalse(conversation.isGenerating)
            assertFalse(conversation.isAwaitingFirstToken)
            assertEquals(null, conversation.runningJob)
            assertEquals("Generation runner is closed", conversation.executionFailure)
        }
        assertEquals(0, writes)
        assertEquals(0, deletes)
        execution.close()
    }

    @Test
    fun rejectedGenerationHandoffClearsPreparedFlagsAndRetainsTheTranscript() = runTest {
        var entries = 0
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (++entries > 1) throw CancellationException("Generation handoff closed")
                return block()
            }
        }
        val execution = HistoryConversationExecution(EmptyHistoryFixture(), EnvelopeChatMessageCodec(), admission)
        val conversation = HistoryConversationState(1, "test")
        val saved = ChatMessage(1, MessageRole.User, "saved")
        conversation.messages += saved
        val runner = OwnedChatGenerationRunner(scope = backgroundScope)
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("Denied model")
        }
        assertFalse(execution.startResponse(conversation, ConversationResponseRequest("scheduled", userMessage = "new"),
            configuration, service, runner, failures))
        assertEquals(listOf(saved), conversation.messages.toList())
        assertFalse(conversation.isGenerating)
        assertFalse(conversation.isAwaitingFirstToken)
        assertEquals(null, conversation.runningJob)
        assertEquals(0, runner.activeTasks.value)
        execution.close()
    }

    @Test
    fun commandFeedbackPublishesOnlyAfterAtomicHistoryCommit() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) {
                assertEquals(listOf("codec:/custom", "codec:feedback"), messages.map { it.content })
                entered.complete(Unit)
                release.await()
                writes += 1
            }
        }
        lateinit var retained: ConversationCommandOperations
        val command = ConversationCommandContribution("custom") { input ->
            if (input != "/custom") null else object : ConversationCommand {
                override val allowedDuringGeneration = true
                override suspend fun execute(request: ConversationCommandRequest) {
                    retained = request.operations
                    val target = checkNotNull(request.conversation)
                    request.operations.appendFeedback(target,
                        ChatMessage(request.operations.nextMessageId(target), MessageRole.User, request.prompt), "feedback")
                }
            }
        }
        val codec = object : ChatMessageCodec by EnvelopeChatMessageCodec() {
            override suspend fun encode(message: ChatMessage): String = "codec:${message.content}"
        }
        val execution = HistoryConversationExecution(repository, codec) { ConversationCommandSnapshot(listOf(command)) }
        val conversation = HistoryConversationState(1, "test")
        var notifications = 0
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("Command must not call model")
        }
        execution.sendMessage("/custom", null, conversation, { error("unexpected new conversation") },
            service, OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions, backgroundScope,
            failures, language, onUserMessageAdded = { _, _ -> notifications += 1 }, followBottom = {})
        entered.await()
        assertTrue(conversation.messages.isEmpty())
        assertEquals(0, notifications)
        assertEquals(3L, conversation.reserveMessageIds())
        release.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, writes)
        assertEquals(listOf("/custom", "feedback"), conversation.messages.map { it.content })
        assertEquals(1, notifications)
        assertFailsWith<IllegalStateException> { retained.nextMessageId(conversation) }
        execution.close()
    }

    @Test
    fun failedCommandFeedbackIsTransientAndDoesNotPublishAnUncommittedTranscript() = runTest {
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) {
                error("storage test-key unavailable")
            }
        }
        val command = ConversationCommandContribution("custom") { input ->
            if (input != "/custom") null else object : ConversationCommand {
                override val allowedDuringGeneration = false
                override suspend fun execute(request: ConversationCommandRequest) {
                    val target = checkNotNull(request.conversation)
                    request.operations.appendFeedback(target, ChatMessage(1, MessageRole.User, request.prompt), "feedback")
                }
            }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec()) { ConversationCommandSnapshot(listOf(command)) }
        val conversation = HistoryConversationState(1, "test")
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
        }
        execution.sendMessage("/custom", configuration, conversation, { error("unexpected") }, service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions, backgroundScope,
            failures, language, onUserMessageAdded = { _, _ -> error("Must not publish") }, followBottom = {})
        testScheduler.runCurrent()
        assertTrue(conversation.messages.isEmpty())
        assertEquals("storage •••• unavailable", conversation.executionFailure)
        execution.close()
    }

    @Test
    fun disposalCancelsAndJoinsGenerationAndRejectsStaleService() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val execution = HistoryConversationExecution(EmptyHistoryFixture(), EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        val runner = OwnedChatGenerationRunner(scope = backgroundScope)
        fun send() = execution.sendMessage(
            "hello", configuration, conversation, { error("unexpected new conversation") },
            service, runner, UnavailableGoalSessions, backgroundScope, failures, language,
            onUserMessageAdded = { _, _ -> }, followBottom = {},
        )
        send()
        entered.await()
        assertTrue(conversation.isGenerating)
        execution.close()
        assertTrue(stopped.isCompleted)
        assertFalse(conversation.isGenerating)
        assertFailsWith<IllegalStateException> { send() }
    }

    @Test
    fun failedHistoryWritePreventsModelRequest() = runTest {
        var requests = 0
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(
                conversationId: Long, title: String, messageId: Long, role: String,
                content: String, isError: Boolean,
            ) {
                error("storage unavailable")
            }
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                requests += 1
                return "answer"
            }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        execution.sendMessage(
            "hello", configuration, conversation, { error("unexpected new conversation") },
            service, OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions,
            backgroundScope, failures, language, onUserMessageAdded = { _, _ -> }, followBottom = {},
        )
        assertEquals(0, requests)
        assertFalse(conversation.isGenerating)
        assertTrue(conversation.messages.isEmpty())
        assertEquals("storage unavailable", conversation.executionFailure)
        assertEquals(null, conversation.runningJob)
        execution.close()
    }

    @Test
    fun failedFinalReplyCommitDoesNotCompleteScheduledResponse() = runTest {
        var writes = 0
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(
                conversationId: Long, title: String, messageId: Long, role: String,
                content: String, isError: Boolean,
            ) {
                writes += 1
                if (role == "Assistant") error("storage test-key unavailable")
            }
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String) = "answer"
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        var completed: Boolean? = null
        val accepted = execution.startResponse(
            conversation,
            ConversationResponseRequest("scheduled prompt", userMessage = "hello",
                scheduledTaskCompletionSession = PersistedScheduledTaskCompletionSession {}),
            configuration, service, OwnedChatGenerationRunner(scope = backgroundScope), failures,
            onResponseFinished = { completed = it },
        )
        assertTrue(accepted)
        assertEquals(false, completed)
        assertEquals(2, writes)
        assertEquals(listOf("hello"), conversation.messages.map { it.content })
        assertEquals("storage •••• unavailable", conversation.executionFailure)
        assertFalse(conversation.isGenerating)
        assertEquals(null, conversation.runningJob)
        execution.close()
    }

    @Test
    fun failedRegenerationDeletePreservesCommittedTranscript() = runTest {
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) {
                error("delete unavailable")
            }
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("Must not call model")
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        val user = ChatMessage(1, MessageRole.User, "hello")
        val answer = ChatMessage(2, MessageRole.Assistant, "saved answer")
        conversation.messages.addAll(listOf(user, answer))
        execution.regenerateMessage(answer, configuration, conversation, service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions, backgroundScope,
            failures, shouldFollowLatest = true, onFollowLatestChange = { error("Must not publish") }, followBottom = {})
        assertEquals(listOf(user, answer), conversation.messages.toList())
        assertEquals("delete unavailable", conversation.executionFailure)
        assertFalse(conversation.isGenerating)
        execution.close()
    }

    @Test
    fun ordinaryUserMessageIsPublishedAfterItsCommit() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(
                conversationId: Long, title: String, messageId: Long, role: String,
                content: String, isError: Boolean,
            ) {
                if (role == "User") {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        var requests = 0
        var notifications = 0
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                requests += 1
                return "answer"
            }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        execution.sendMessage("hello", configuration, conversation, { error("unexpected") }, service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions, backgroundScope,
            failures, language, onUserMessageAdded = { _, _ -> notifications += 1 }, followBottom = {})
        entered.await()
        assertTrue(conversation.messages.isEmpty())
        assertTrue(conversation.isGenerating)
        assertEquals(0, requests)
        assertEquals(0, notifications)
        release.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, requests)
        assertEquals(1, notifications)
        assertEquals(listOf("hello", "answer"), conversation.messages.map { it.content })
        execution.close()
    }

    @Test
    fun setupFeedbackRequiresAnAtomicCommit() = runTest {
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessages(conversationId: Long, title: String, messages: List<HistoryMessageWrite>) {
                assertEquals(listOf("hello", "setup"), messages.map { it.content })
                error("batch unavailable")
            }
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        execution.sendMessage("hello", null, conversation, { error("unexpected") }, service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions, backgroundScope,
            failures, language, onUserMessageAdded = { _, _ -> error("Must not publish") }, followBottom = {})
        testScheduler.runCurrent()
        assertTrue(conversation.messages.isEmpty())
        assertEquals("batch unavailable", conversation.executionFailure)
        assertFalse(conversation.isGenerating)
        execution.close()
    }

    @Test
    fun cancellationDropsPartialReplyIfItsCommitFails() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(
                conversationId: Long, title: String, messageId: Long, role: String,
                content: String, isError: Boolean,
            ) {
                if (role == "Assistant") error("partial write unavailable")
            }
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
            override suspend fun replyStreaming(
                configuration: ModelConfiguration,
                history: List<ChatMessage>,
                prompt: String,
                goalSession: GoalSession?,
                scheduledTaskSession: ScheduledTaskSession?,
                scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
                onToolUse: suspend (ToolUseEvent) -> Unit,
                onSubAgent: suspend (SubAgentEvent) -> Unit,
                onDelta: suspend (String) -> Unit,
            ): String {
                onDelta("partial")
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val execution = HistoryConversationExecution(repository, EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        execution.sendMessage("hello", configuration, conversation, { error("unexpected") }, service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableGoalSessions, backgroundScope,
            failures, language, onUserMessageAdded = { _, _ -> }, followBottom = {})
        entered.await()
        assertEquals(listOf("hello", "partial"), conversation.messages.map { it.content })
        execution.close()
        assertEquals(listOf("hello"), conversation.messages.map { it.content })
        assertEquals("partial write unavailable", conversation.executionFailure)
        assertFalse(conversation.isGenerating)
    }

    @Test
    fun disposalWaitsForScheduledCompletionCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String) = "answer"
        }
        val execution = HistoryConversationExecution(EmptyHistoryFixture(), EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        execution.startResponse(
            conversation,
            ConversationResponseRequest("scheduled prompt", userMessage = "hello",
                scheduledTaskCompletionSession = PersistedScheduledTaskCompletionSession {}),
            configuration, service, OwnedChatGenerationRunner(scope = backgroundScope), failures,
            onResponseFinished = {
                assertTrue(it)
                entered.complete(Unit)
                release.await()
            },
        )
        entered.await()
        val closing = launch { execution.close() }
        testScheduler.runCurrent()
        assertFalse(closing.isCompleted)
        assertTrue(conversation.isGenerating)
        release.complete(Unit)
        closing.join()
        assertFalse(conversation.isGenerating)
        assertEquals(null, conversation.runningJob)
    }

    @Test
    fun genericResponseReportsCancellationToTheOwningFeature() = runTest {
        var cancellations = 0
        var failureEvents = 0
        var completed: Boolean? = null
        val entered = CompletableDeferred<Unit>()
        val lifecycle = object : ConversationResponseLifecycle {
            override suspend fun onCancelled() { cancellations += 1 }
            override suspend fun onFailed() { failureEvents += 1 }
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                assertEquals("feature prompt", prompt)
                assertTrue(history.isEmpty())
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val execution = HistoryConversationExecution(EmptyHistoryFixture(), EnvelopeChatMessageCodec())
        val conversation = HistoryConversationState(1, "test")
        assertTrue(execution.startResponse(conversation,
            ConversationResponseRequest("feature prompt", lifecycle = lifecycle),
            configuration, service, OwnedChatGenerationRunner(scope = backgroundScope), failures,
            onResponseFinished = { completed = it }))
        entered.await()
        execution.close()
        assertEquals(1, cancellations)
        assertEquals(0, failureEvents)
        assertEquals(false, completed)
        assertTrue(conversation.messages.isEmpty())
        assertFalse(conversation.isGenerating)
    }
}
