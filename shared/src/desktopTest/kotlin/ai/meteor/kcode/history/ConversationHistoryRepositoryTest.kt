package ai.meteor.kcode.history

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
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

    @Test
    fun storesAndClearsAConversationGoalWithoutRequiringMessages() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()
        val goal = ThreadGoal(
            goalId = "goal-1",
            objective = "Finish the migration",
            status = ThreadGoalStatus.Active,
            tokenBudget = 5_000,
            tokensUsed = 120,
            timeUsedSeconds = 9,
            createdAt = 10,
            updatedAt = 20,
        )

        repository.setGoal(7, "Migration", goal)

        assertEquals(goal, repository.loadAll().single().goal)
        assertTrue(repository.loadAll().single().messages.isEmpty())

        repository.clearGoal(7)
        assertEquals(null, repository.loadAll().single().goal)
    }

    @Test
    fun persistsScheduledTasksAndDeletesThemWithTheirConversation() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()
        val task = ScheduledTask(
            taskId = "task-1",
            conversationId = 9L,
            name = "Daily summary",
            prompt = "Summarize today's changes.",
            status = ScheduledTaskStatus.Active,
            nextRunAt = 10_000L,
            repeatIntervalMillis = 86_400_000L,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )

        repository.upsertScheduledTask("Summary", task)

        assertEquals(task, repository.loadScheduledTasks().single())
        assertEquals(9L, repository.loadAll().single().id)

        repository.deleteConversation(9L)

        assertTrue(repository.loadScheduledTasks().isEmpty())
    }

    @Test
    fun persistsFloatingPresentationAndPromotesIt() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()

        repository.createConversation(12L, "Standalone", ConversationPresentation.Floating)
        assertEquals(ConversationPresentation.Floating, repository.loadAll().single().presentation)

        repository.setConversationPresentation(12L, ConversationPresentation.Recent)
        assertEquals(ConversationPresentation.Recent, repository.loadAll().single().presentation)
    }

    @Test
    fun persistsPendingStandaloneConversationBeforeItsResponseIsVisible() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()

        repository.createConversation(13L, "Pending", ConversationPresentation.PendingStandalone)

        val stored = repository.loadAll().single()
        assertEquals(ConversationPresentation.PendingStandalone, stored.presentation)
        assertTrue(stored.messages.isEmpty())
    }

    @Test
    fun persistsLlmSelectedStandaloneResult() = runBlocking {
        val repository = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .buildHistoryRepository()
        repository.createConversation(14L, "Result", ConversationPresentation.PendingStandalone)

        repository.setStandaloneResult(14L, "Deployment completed successfully.")

        assertEquals("Deployment completed successfully.", repository.loadAll().single().standaloneResult)
    }

    @Test
    fun softDeleteRetainsOnlyTheNewestHundredDeletedConversations() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder<HistoryDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
        val repository = RoomConversationHistoryRepository(database)

        repeat(101) { index ->
            val id = index + 1L
            repository.createConversation(id, "Conversation $id")
            repository.deleteConversation(id)
        }

        assertTrue(repository.loadAll().isEmpty())
        assertEquals(100, database.historyDao().countDeletedConversations())
        assertEquals(null, database.historyDao().loadConversation(1L))
        assertTrue(requireNotNull(database.historyDao().loadConversation(101L)).isDeleted)
        assertEquals(102L, repository.nextConversationId())
    }
}
