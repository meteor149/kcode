package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.HistoryMessageWrite
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.ScheduledTaskStatus
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MemoryHistoryRepositoryTest {
    @Test
    fun instancesOwnIndependentConversationAndTaskState() = runTest {
        val first = MemoryHistoryRepository()
        val second = MemoryHistoryRepository()
        first.appendMessage(1, "one", 1, "User", "saved", false)
        first.upsertScheduledTask("one", task())
        assertEquals("saved", first.loadAll().single().messages.single().content)
        assertEquals(1, first.loadScheduledTasks().size)
        assertTrue(second.loadAll().isEmpty())
        assertTrue(second.loadScheduledTasks().isEmpty())
        first.close()
        assertFailsWith<IllegalStateException> { first.loadAll() }
        second.createConversation(1, "other", ConversationPresentation.Recent)
        assertEquals("other", second.loadAll().single().title)
        second.close()
    }

    @Test
    fun duplicateBatchFailsWithoutAnyPartialCommitAndSnapshotsRemainDetached() = runTest {
        val repository = MemoryHistoryRepository()
        assertFailsWith<IllegalArgumentException> {
            repository.appendMessages(1, "invalid", listOf(HistoryMessageWrite(1, "User", "a"), HistoryMessageWrite(1, "Assistant", "b")))
        }
        assertTrue(repository.loadAll().isEmpty())
        repository.appendMessages(1, "valid", listOf(HistoryMessageWrite(2, "User", "a"), HistoryMessageWrite(1, "Assistant", "b")))
        val snapshot = repository.loadAll().single()
        assertEquals(listOf("a", "b"), snapshot.messages.map { it.content })
        repository.deleteMessagesFrom(1, 2)
        assertEquals(listOf("b"), repository.loadAll().single().messages.map { it.content })
        assertEquals(2, snapshot.messages.size)
        repository.close()
    }

    @Test
    fun metadataGoalAndTaskChangesAreCommittedAndDeletionDoesNotReuseIdentifiers() = runTest {
        val repository = MemoryHistoryRepository()
        repository.createConversation(1, "one", ConversationPresentation.PendingStandalone)
        repository.setConversationPresentation(1, ConversationPresentation.Floating)
        repository.setStandaloneResult(1, "result")
        repository.setPinned(1, true)
        repository.setGoal(1, "one", ThreadGoal("g", "work", ThreadGoalStatus.Active, createdAt = 1, updatedAt = 1))
        val stored = repository.loadAll().single()
        assertEquals(ConversationPresentation.Floating, stored.presentation)
        assertEquals("result", stored.standaloneResult)
        assertTrue(stored.isPinned)
        assertEquals("work", stored.goal?.objective)
        repository.clearGoal(1)
        assertEquals(null, repository.loadAll().single().goal)
        repository.upsertScheduledTask("one", task())
        repository.updateScheduledTask(task().copy(name = "updated"))
        assertEquals("updated", repository.loadScheduledTasks(1).single().name)
        repository.deleteScheduledTask(2, "t")
        assertEquals(1, repository.loadScheduledTasks().size)
        repository.deleteScheduledTask(1, "t")
        assertTrue(repository.loadScheduledTasks().isEmpty())
        repository.upsertScheduledTask("one", task())
        repository.deleteConversation(1)
        assertTrue(repository.loadAll().isEmpty())
        assertTrue(repository.loadScheduledTasks().isEmpty())
        assertEquals(2L, repository.nextConversationId())
        repository.close()
    }

    private fun task() = ScheduledTask("t", 1, "task", "do work", ScheduledTaskStatus.Active, 10, createdAt = 1, updatedAt = 1)
}
