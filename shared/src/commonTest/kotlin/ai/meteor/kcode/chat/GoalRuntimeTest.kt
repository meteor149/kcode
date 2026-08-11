package ai.meteor.kcode.chat

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalRuntimeTest {
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
        val conversation = ConversationState(9, "Work")
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
        val conversation = ConversationState(1, "Budget")
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

private class RecordingGoalRepository : ConversationHistoryRepository {
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
        this.goal = goal
    }
    override suspend fun clearGoal(conversationId: Long) {
        goal = null
        clearCount++
    }
    override suspend fun deleteConversation(conversationId: Long) = Unit
}
