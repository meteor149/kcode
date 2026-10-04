package ai.meteor.kcode.plugin

import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.chat.ConversationCommandOperations
import ai.meteor.kcode.chat.ConversationCommandRequest
import ai.meteor.kcode.plugin.goal.ConversationGoalSession
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalCommandHandlerTest {
    @Test
    fun settingGoalCommitsStateBeforeStartingTheResponseAndFailedEditPreservesIt() = runTest {
        var stored: ThreadGoal? = null
        var failWrites = false
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) {
                if (failWrites) error("goal write failed")
                stored = goal
            }
        }
        val conversation = HistoryConversationState(1, "Goal command")
        val session = ConversationGoalSession(conversation, repository) { 100L }
        val contribution = GoalCommandHandler(GoalSessionFactory { session }, localize = { _, _ -> "label" }).contribution()
        var responses = 0
        var feedback: String? = null
        val operations = object : ConversationCommandOperations {
            override fun nextMessageId(target: ConversationState) = 1L
            override suspend fun appendFeedback(target: ConversationState, user: ChatMessage, content: String, isError: Boolean) {
                assertTrue(isError)
                feedback = content
            }
            override suspend fun startResponse(target: ConversationState, user: ChatMessage, prompt: String, goalSession: GoalSession?) {
                assertEquals(stored, target.goal)
                assertContains(prompt, "Finish plugins")
                assertEquals(session, goalSession)
                responses += 1
            }
        }
        suspend fun execute(prompt: String) = checkNotNull(contribution.match(prompt)).execute(
            ConversationCommandRequest(prompt, AppLanguage.English, conversation, { error("unexpected") }, operations),
        )
        execute("/goal Finish plugins")
        val committed = conversation.goal
        assertEquals("Finish plugins", committed?.objective)
        assertEquals(1, responses)
        assertNull(feedback)
        failWrites = true
        execute("/goal edit Unsaved")
        assertEquals(committed, conversation.goal)
        assertEquals(committed, stored)
        assertEquals("goal write failed", feedback)
        assertEquals(1, responses)
    }

    @Test
    fun pauseWaitsForGenerationCancellationBeforeCommittingStatusAndFeedback() = runTest {
        val conversation = HistoryConversationState(1, "Goal command")
        val session = ConversationGoalSession(conversation, EmptyHistoryFixture()) { 100L }
        session.createGoal("Running goal")
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val running = backgroundScope.launch {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        conversation.runningJob = running
        started.await()
        var feedback = ""
        val operations = object : ConversationCommandOperations {
            override fun nextMessageId(target: ConversationState) = 1L
            override suspend fun appendFeedback(target: ConversationState, user: ChatMessage, content: String, isError: Boolean) {
                assertTrue(stopped.isCompleted)
                assertEquals(ThreadGoalStatus.Paused, target.goal?.status)
                feedback = content
            }
            override suspend fun startResponse(target: ConversationState, user: ChatMessage, prompt: String, goalSession: GoalSession?) = error("Pause must not call model")
        }
        val command = checkNotNull(GoalCommandHandler(GoalSessionFactory { session }, localize = { _, _ -> "label" }).contribution().match("/goal pause"))
        assertTrue(command.allowedDuringGeneration)
        command.execute(ConversationCommandRequest("/goal pause", AppLanguage.English, conversation,
            { error("unexpected") }, operations))
        assertContains(feedback, "Running goal")
        assertEquals(ThreadGoalStatus.Paused, conversation.goal?.status)
    }
}
