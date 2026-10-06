package ai.meteor.kcode.plugin.goalui

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
    fun disposalCancelsAndJoinsPendingButtonWorkAndWithdrawnCallbacksStayQuiet() = runTest {
        val conversation = HistoryConversationState(1, "Goal UI", initialGoal = ThreadGoal(
            "goal", "Existing", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1,
        ))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
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
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        val execution = object : ConversationExecution {
            override fun sendMessage(prompt: String, configuration: ModelConfiguration?, conversation: ConversationState?, onSendToNew: (String) -> ConversationState,
                service: ChatService, generationRunner: ChatGenerationRunner, goalSessionFactory: GoalSessionFactory, scope: kotlinx.coroutines.CoroutineScope,
                failureMessages: ChatFailureMessages, language: ai.meteor.kcode.localization.AppLanguage,
                scheduledTaskSessionFor: (ConversationState) -> ai.meteor.kcode.chat.ScheduledTaskSession?,
                onUserMessageAdded: (ConversationState, ChatMessage) -> Unit, followBottom: (ConversationState) -> Unit) = error("unused")
            override suspend fun startResponse(target: ConversationState, request: ai.meteor.kcode.chat.ConversationResponseRequest,
                configuration: ModelConfiguration?, service: ChatService, generationRunner: ChatGenerationRunner,
                failureMessages: ChatFailureMessages, followBottom: (ConversationState) -> Unit,
                onResponseFinished: suspend (Boolean) -> Unit): Boolean = error("unused")
            override fun regenerateMessage(answer: ChatMessage, configuration: ModelConfiguration?, conversation: ConversationState?, service: ChatService,
                generationRunner: ChatGenerationRunner, goalSessionFactory: GoalSessionFactory, scope: kotlinx.coroutines.CoroutineScope, failureMessages: ChatFailureMessages,
                scheduledTaskSession: ai.meteor.kcode.chat.ScheduledTaskSession?, shouldFollowLatest: Boolean, onFollowLatestChange: (Boolean) -> Unit,
                followBottom: (ConversationState) -> Unit) = error("unused")
        }
        val service = object : ChatService {
            override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String = error("unused")
        }
        val context = ConversationPageContext(conversation, true, null, service, OwnedChatGenerationRunner(scope = backgroundScope),
            UnavailableScheduledTasks, ChatFailureMessages("setup", "connection"), {})
        val actions = GoalUiActions(GoalSessionFactory { session }, execution)
        actions.dispatch(backgroundScope, context, ThreadGoalStatus.Paused)
        entered.await()
        actions.close()
        assertTrue(stopped.isCompleted)
        assertEquals(ThreadGoalStatus.Active, conversation.goal?.status)
        actions.dispatch(backgroundScope, context, ThreadGoalStatus.Paused)
        testScheduler.runCurrent()
        assertEquals(1, writes)
        assertEquals(null, conversation.executionFailure)
    }
}
