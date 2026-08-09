package ai.meteor.kcode.history

import androidx.room3.Room
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationHistoryRepositoryTest {
    @Test
    fun storesMessagesAtomicallyAndOrdersRecentConversations() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()

        repository.appendMessage(1, "First", 1, "User", "hello")
        repository.appendMessage(2, "Second", 1, "User", "another")
        repository.appendMessage(1, "First", 2, "Assistant", "reply", isError = true)

        val conversations = repository.loadAll()

        assertEquals(listOf(1L, 2L), conversations.map { it.id })
        assertEquals(listOf("hello", "reply"), conversations.first().messages.map { it.content })
        assertTrue(conversations.first().messages.last().isError)
    }

    @Test
    fun deletesTargetMessageAndEverythingAfterIt() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()

        repository.appendMessage(1, "Chat", 1, "User", "question")
        repository.appendMessage(1, "Chat", 2, "Assistant", "first answer")
        repository.appendMessage(1, "Chat", 3, "User", "follow-up")
        repository.appendMessage(1, "Chat", 4, "Assistant", "second answer")

        repository.deleteMessagesFrom(1, 2)

        assertEquals(listOf("question"), repository.loadAll().single().messages.map { it.content })
    }

    @Test
    fun pinsAndDeletesWholeConversations() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()

        repository.appendMessage(1, "Older", 1, "User", "first")
        repository.appendMessage(2, "Newer", 1, "User", "second")
        repository.setPinned(1, true)

        val pinned = repository.loadAll()
        assertEquals(listOf(1L, 2L), pinned.map { it.id })
        assertTrue(pinned.first().isPinned)

        repository.deleteConversation(1)

        assertEquals(listOf(2L), repository.loadAll().map { it.id })
    }
}
