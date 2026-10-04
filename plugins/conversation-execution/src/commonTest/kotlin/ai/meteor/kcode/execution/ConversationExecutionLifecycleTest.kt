package ai.meteor.kcode.execution

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ConversationExecutionLifecycleTest {
    private val configuration = ModelConfiguration(ModelProvider.DeepSeek, "test", "test-key", temperature = 0.6)
    private val failures = ChatFailureMessages("setup", "connection")
    private val language = AppLanguage.English

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
