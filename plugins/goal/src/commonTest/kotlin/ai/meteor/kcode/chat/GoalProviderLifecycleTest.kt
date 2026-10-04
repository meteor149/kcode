package ai.meteor.kcode.chat

import ai.meteor.kcode.session.HistoryConversationState

import ai.meteor.kcode.plugin.goal.HistoryGoalSessions
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalProviderLifecycleTest {
    @Test
    fun disposalCancelsAndJoinsWritesAndRejectsRetainedFactoryAndSession() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                    }
                }
            }
        }
        val factory = HistoryGoalSessions(repository)
        val conversation = HistoryConversationState(1, "test")
        val session = factory.create(conversation)
        assertTrue(session === factory.create(conversation))
        val writing = launch { session.setGoalFromUser("unsaved") }
        entered.await()
        val closing = launch { factory.close() }
        cleaning.await()
        assertFalse(closing.isCompleted)
        assertEquals(null, conversation.goal)
        release.complete(Unit)
        closing.join()
        writing.join()
        assertTrue(writing.isCancelled)
        assertFailsWith<IllegalStateException> { factory.create(conversation) }
        assertFailsWith<IllegalStateException> { session.getGoal() }
        assertFailsWith<IllegalStateException> { session.continuationPrompt() }
        assertFailsWith<IllegalStateException> { session.setGoalFromUser("stale") }
        assertFailsWith<IllegalStateException> { session.onCancelled() }
    }

    @Test
    fun ownerRejectsSelfDisposalAndStillClosesFromOutside() = runTest {
        val owner = PluginOperationOwner("test")
        owner.run { assertFailsWith<IllegalStateException> { owner.close() } }
        assertEquals(7, owner.run { 7 })
        owner.close()
        owner.close()
        assertFailsWith<IllegalStateException> { owner.run { error("Must not run") } }
    }
}
