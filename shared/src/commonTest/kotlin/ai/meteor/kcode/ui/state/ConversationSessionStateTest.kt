package ai.meteor.kcode.ui.state

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.StoredConversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionStateTest {
    @Test
    fun toggleConversationPinnedPinsAnUnpinnedConversation() = runTest {
        val repository = RecordingConversationHistoryRepository()
        val state = ConversationSessionState(repository, this)
        state.conversations += ConversationState(id = 1L, initialTitle = "First")
        state.conversations += ConversationState(id = 2L, initialTitle = "Second")

        state.toggleConversationPinned(2L)
        runCurrent()

        assertEquals(listOf(2L, 1L), state.conversations.map { it.id })
        assertTrue(state.conversations.first().isPinned)
        assertEquals(listOf(2L to true), repository.pinChanges)
    }

    @Test
    fun toggleConversationPinnedUnpinsAPinnedConversation() = runTest {
        val repository = RecordingConversationHistoryRepository()
        val state = ConversationSessionState(repository, this)
        state.conversations += ConversationState(id = 1L, initialTitle = "First", initialPinned = true)
        state.conversations += ConversationState(id = 2L, initialTitle = "Second", initialPinned = true)
        state.conversations += ConversationState(id = 3L, initialTitle = "Third")

        state.toggleConversationPinned(1L)
        runCurrent()

        assertEquals(listOf(2L, 1L, 3L), state.conversations.map { it.id })
        assertFalse(state.conversations[1].isPinned)
        assertEquals(listOf(1L to false), repository.pinChanges)
    }
}

private class RecordingConversationHistoryRepository : ConversationHistoryRepository {
    val pinChanges = mutableListOf<Pair<Long, Boolean>>()

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

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) {
        pinChanges += conversationId to pinned
    }

    override suspend fun deleteConversation(conversationId: Long) = Unit
}
