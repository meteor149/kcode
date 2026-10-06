package ai.meteor.kcode.plugin.goalui

import ai.meteor.kcode.plugin.api.ExecutionAdmission
import ai.meteor.kcode.plugin.goal.ConversationGoalSession
import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.model.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.plugin.execution.OwnedChatGenerationRunner
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalUiLifecycleTest {
    @Test
    fun closedAdmissionPreservesGoalResumeAndRunningResponseForEveryButton() = runTest {
        val conversation = HistoryConversationState(1, "Goal UI", initialGoal = ThreadGoal(
            "goal", "Existing", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1,
        ))
        conversation.shouldResumeGoal = true
        val running = backgroundScope.launch { awaitCancellation() }
        conversation.runningJob = running
        var allocations = 0
        val sessions = GoalSessionFactory { allocations++; error("Denied session allocation") }
        val closed = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T = throw CancellationException("Closed")
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
        }
        val context = ConversationPageContext(conversation, true,
            ModelConfiguration(ModelProvider.DeepSeek, "test", "", 0.4), service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableScheduledTasks,
            ChatFailureMessages("setup", "connection"), {})
        val actions = GoalUiActions(sessions, execution(), closed)
        for (action in (listOf<ThreadGoalStatus?>(null) + ThreadGoalStatus.entries)) {
            actions.dispatch(backgroundScope, context, action)
        }
        testScheduler.runCurrent()
        assertFailsWith<CancellationException> { restoreGoal(context, sessions, execution(), closed) }
        assertEquals(0, allocations)
        assertTrue(conversation.shouldResumeGoal)
        assertTrue(running.isActive)
        assertEquals(ThreadGoalStatus.Active, conversation.goal?.status)
        assertEquals(null, conversation.executionFailure)
        actions.close()
        running.cancel()
    }

    @Test
    fun rejectedOrCancelledRestorationRetainsResumeIntentUntilResponseAcceptance() = runTest {
        val conversation = HistoryConversationState(1, "Goal UI", initialGoal = ThreadGoal(
            "goal", "Existing", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1,
        ))
        conversation.shouldResumeGoal = true
        val sessions = GoalSessionFactory { ConversationGoalSession(it, EmptyHistoryFixture()) }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
        }
        val context = ConversationPageContext(conversation, true,
            ModelConfiguration(ModelProvider.DeepSeek, "test", "", 0.4), service,
            OwnedChatGenerationRunner(scope = backgroundScope), UnavailableScheduledTasks,
            ChatFailureMessages("setup", "connection"), {})
        restoreGoal(context, sessions, execution { false })
        assertTrue(conversation.shouldResumeGoal)
        assertFailsWith<CancellationException> { restoreGoal(context, sessions, execution { throw CancellationException("Cancelled") }) }
        assertTrue(conversation.shouldResumeGoal)
        restoreGoal(context, sessions, execution { true })
        assertFalse(conversation.shouldResumeGoal)
    }

    @Test
    fun disposalCancelsAndJoinsPendingButtonWorkAndWithdrawnCallbacksStayQuiet() = runTest {
        val conversation = HistoryConversationState(1, "Goal UI", initialGoal = ThreadGoal(
            "goal", "Existing", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1,
        ))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var admitted = 0
        val admission = object : ExecutionAdmission {
            override suspend fun <T> run(block: suspend () -> T): T {
                admitted++
                try { return block() } finally { admitted-- }
            }
        }
        var writes = 0
        val session = object : GoalSession {
            override suspend fun getGoal() = conversation.goal
            override suspend fun createGoal(objective: String, tokenBudget: Long?): ThreadGoal = error("unused")
            override suspend fun setGoalFromUser(objective: String): ThreadGoal = error("unused")
            override suspend fun editGoalFromUser(objective: String): ThreadGoal = error("unused")
            override suspend fun updateGoalFromAgent(status: ThreadGoalStatus): ThreadGoal = error("unused")
            override suspend fun clearGoal() = error("unused")
            override suspend fun recordUsage(tokens: Long, elapsedSeconds: Long): ThreadGoal? = error("unused")
            override suspend fun setStatusFromUser(status: ThreadGoalStatus): ThreadGoal {
                writes += 1
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { stopped.complete(Unit); releaseCleanup.await() } }
            }
        }
        val execution = execution()
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
        }
        val context = ConversationPageContext(conversation, true, null, service, OwnedChatGenerationRunner(scope = backgroundScope),
            UnavailableScheduledTasks, ChatFailureMessages("setup", "connection"), {})
        val actions = GoalUiActions(GoalSessionFactory { session }, execution, admission)
        actions.dispatch(backgroundScope, context, ThreadGoalStatus.Paused)
        entered.await()
        assertEquals(1, admitted)
        val closing = launch { actions.close() }
        stopped.await()
        assertFalse(closing.isCompleted)
        assertEquals(1, admitted)
        releaseCleanup.complete(Unit)
        closing.join()
        assertEquals(0, admitted)
        assertTrue(stopped.isCompleted)
        assertEquals(ThreadGoalStatus.Active, conversation.goal?.status)
        actions.dispatch(backgroundScope, context, ThreadGoalStatus.Paused)
        testScheduler.runCurrent()
        assertEquals(1, writes)
        assertEquals(null, conversation.executionFailure)
    }

    private fun execution(respond: suspend () -> Boolean = { error("unused") }): ConversationExecution = object : ConversationExecution {
        override fun sendMessage(prompt: String, configuration: ModelConfiguration?, conversation: ConversationState?, onSendToNew: (String) -> ConversationState,
            service: ChatService, generationRunner: ChatGenerationRunner, goalSessionFactory: GoalSessionFactory, scope: kotlinx.coroutines.CoroutineScope,
            failureMessages: ChatFailureMessages, language: ai.meteor.kcode.localization.AppLanguage,
            scheduledTaskSessionFor: (ConversationState) -> ai.meteor.kcode.chat.ScheduledTaskSession?,
            onUserMessageAdded: (ConversationState, ChatMessage) -> Unit, followBottom: (ConversationState) -> Unit) = error("unused")
        override suspend fun startResponse(target: ConversationState, request: ai.meteor.kcode.chat.ConversationResponseRequest,
            configuration: ModelConfiguration?, service: ChatService, generationRunner: ChatGenerationRunner,
            failureMessages: ChatFailureMessages, followBottom: (ConversationState) -> Unit,
            onResponseFinished: suspend (Boolean) -> Unit): Boolean = respond()
        override fun regenerateMessage(answer: ChatMessage, configuration: ModelConfiguration?, conversation: ConversationState?, service: ChatService,
            generationRunner: ChatGenerationRunner, goalSessionFactory: GoalSessionFactory, scope: kotlinx.coroutines.CoroutineScope, failureMessages: ChatFailureMessages,
            scheduledTaskSession: ai.meteor.kcode.chat.ScheduledTaskSession?, shouldFollowLatest: Boolean, onFollowLatestChange: (Boolean) -> Unit,
            followBottom: (ConversationState) -> Unit) = error("unused")
    }

}
