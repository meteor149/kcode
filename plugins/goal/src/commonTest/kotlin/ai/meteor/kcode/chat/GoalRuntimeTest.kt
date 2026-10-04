package ai.meteor.kcode.chat

import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.plugin.goal.goalContinuationPrompt
import ai.meteor.kcode.plugin.goal.parseGoalCommand
import ai.meteor.kcode.plugin.goal.GoalCommand
import ai.meteor.kcode.plugin.goal.ConversationGoalSession
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalRuntimeTest {
    @Test
    fun responseTerminationPolicyOnlyTransitionsAnActiveGoal() = runTest {
        val repository = RecordingGoalRepository()
        val conversation = HistoryConversationState(9, "Policy")
        val session = ConversationGoalSession(conversation, repository) { 100L }
        session.setGoalFromUser("Continue")
        assertContains(checkNotNull(session.continuationPrompt()), "Continue")
        session.onCancelled()
        assertEquals(ThreadGoalStatus.Paused, conversation.goal?.status)
        assertEquals(null, session.continuationPrompt())
        session.onFailed()
        assertEquals(ThreadGoalStatus.Paused, conversation.goal?.status)
        session.setStatusFromUser(ThreadGoalStatus.Active)
        session.onFailed()
        assertEquals(ThreadGoalStatus.Blocked, conversation.goal?.status)
        session.setStatusFromUser(ThreadGoalStatus.Complete)
        session.onCancelled()
        assertEquals(ThreadGoalStatus.Complete, conversation.goal?.status)
        session.setStatusFromUser(ThreadGoalStatus.Active)
        repository.failWrites = true
        assertFailsWith<IllegalStateException> { session.onCancelled() }
        assertEquals(ThreadGoalStatus.Active, conversation.goal?.status)
    }

    @Test
    fun failedPersistenceDoesNotPublishUncommittedGoalState() = runTest {
        val repository = RecordingGoalRepository()
        val conversation = HistoryConversationState(9, "Atomic goal")
        val session = ConversationGoalSession(conversation, repository) { 100L }
        repository.failWrites = true
        assertFailsWith<IllegalStateException> { session.createGoal("Unsaved") }
        assertEquals(null, conversation.goal)
        repository.failWrites = false
        val committed = session.createGoal("Saved")
        repository.failWrites = true
        assertFailsWith<IllegalStateException> { session.editGoalFromUser("Unsaved edit") }
        assertEquals(committed, conversation.goal)
        assertFailsWith<IllegalStateException> { session.clearGoal() }
        assertEquals(committed, conversation.goal)
        assertEquals(committed, repository.goal)
    }

    @Test
    fun parsesTheCodexCliGoalCommands() {
        assertEquals(GoalCommand.Show, parseGoalCommand("/goal"))
        assertEquals(GoalCommand.Pause, parseGoalCommand("/goal pause"))
        assertEquals(GoalCommand.Resume, parseGoalCommand("/goal resume"))
        assertEquals(GoalCommand.Clear, parseGoalCommand("/goal clear"))
        assertEquals(GoalCommand.Edit("new target"), parseGoalCommand("/goal edit new target"))
        assertEquals(GoalCommand.Set("ship it"), parseGoalCommand("/goal ship it"))
        assertEquals(null, parseGoalCommand("please /goal ship it"))
    }

    @Test
    fun persistsLifecycleAndEnforcesAgentStatusBoundary() = runTest {
        val repository = RecordingGoalRepository()
        val conversation = HistoryConversationState(9, "Work")
        var timestamp = 100L
        val session = ConversationGoalSession(conversation, repository) { timestamp++ }

        val created = session.setGoalFromUser("Finish everything")
        assertEquals(ThreadGoalStatus.Active, created.status)
        assertEquals(created, repository.goal)

        session.recordUsage(tokens = 25, elapsedSeconds = 2)
        val editedFromCommand = session.setGoalFromUser("Finish everything and verify")
        assertEquals(created.goalId, editedFromCommand.goalId)
        assertEquals(25, editedFromCommand.tokensUsed)

        val paused = session.setStatusFromUser(ThreadGoalStatus.Paused)
        assertEquals(ThreadGoalStatus.Paused, paused.status)
        session.setStatusFromUser(ThreadGoalStatus.Active)

        assertFailsWith<IllegalArgumentException> {
            session.updateGoalFromAgent(ThreadGoalStatus.Paused)
        }
        assertEquals(ThreadGoalStatus.Complete, session.updateGoalFromAgent(ThreadGoalStatus.Complete).status)

        session.clearGoal()
        assertEquals(null, conversation.goal)
        assertEquals(1, repository.clearCount)
    }

    @Test
    fun accountsTokensAndStopsAtTheExplicitBudget() = runTest {
        val conversation = HistoryConversationState(1, "Budget")
        val session = ConversationGoalSession(conversation, RecordingGoalRepository()) { 10L }
        session.createGoal("Bounded task", tokenBudget = 100)

        session.recordUsage(tokens = 40, elapsedSeconds = 3)
        val limited = session.recordUsage(tokens = 60, elapsedSeconds = 2)

        assertEquals(ThreadGoalStatus.BudgetLimited, limited?.status)
        assertEquals(100, limited?.tokensUsed)
        assertEquals(5, limited?.timeUsedSeconds)
    }

    @Test
    fun continuationPromptUsesTheCodexGoalEnvelopeAndEscapesObjective() {
        val prompt = goalContinuationPrompt(
            ThreadGoal("id", "fix <all> & verify", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1),
        )

        assertContains(prompt, "<objective>\nfix &lt;all&gt; &amp; verify\n</objective>")
        assertContains(prompt, "Tokens remaining: unbounded")
        assertContains(prompt, "Completion audit:")
        assertContains(prompt, "Blocked audit:")
    }
}

private class RecordingGoalRepository : ConversationHistoryRepository by EmptyHistoryFixture() {
    var failWrites = false
    var goal: ThreadGoal? = null
    var clearCount = 0

    override suspend fun loadAll(): List<StoredConversation> = emptyList()
    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) = Unit
    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) = Unit
    override suspend fun setPinned(conversationId: Long, pinned: Boolean) = Unit
    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) {
        check(!failWrites) { "storage failed" }
        this.goal = goal
    }
    override suspend fun clearGoal(conversationId: Long) {
        check(!failWrites) { "storage failed" }
        goal = null
        clearCount++
    }
    override suspend fun deleteConversation(conversationId: Long) = Unit
}
