package ai.meteor.kcode.session

import ai.meteor.kcode.test.EmptyHistoryFixture

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.StoredConversation
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.StoredMessage
import ai.meteor.kcode.model.MessageRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ai.meteor.kcode.plugin.messagecodec.EnvelopeChatMessageCodec
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.DecodedStoredMessageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionStateTest {
    @Test
    fun historyLoadUsesInjectedCodecForBothUserAndAssistantContent() = runTest {
        val inputs = mutableListOf<String>()
        val codec = object : ChatMessageCodec by EnvelopeChatMessageCodec() {
            override suspend fun decode(content: String): DecodedStoredMessageContent {
                inputs += content
                return DecodedStoredMessageContent(content.removePrefix("codec:"), emptyList(), emptyList())
            }
        }
        val repository = RecordingConversationHistoryRepository(listOf(
            storedConversation(1L, ConversationPresentation.Recent, messages = listOf(
                storedMessage(1L, MessageRole.User).copy(content = "codec:question"),
                storedMessage(1L, MessageRole.Assistant).copy(id = 2L, content = "codec:answer"),
            )),
        ))
        val session = HistoryConversationSession(repository, this, codec)
        try {
            session.load()
            assertEquals(listOf("codec:question", "codec:answer"), inputs)
            assertEquals(listOf("question", "answer"), session.conversations.single().messages.map { it.content })
        } finally {
            session.close()
        }
    }

    @Test
    fun failedMutationsKeepCommittedProjectionAndCanBeRetried() = runTest {
        val repository = RecordingConversationHistoryRepository(
            stored = listOf(storedConversation(1L, ConversationPresentation.Recent)),
        )
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())
        repository.failReads = true
        state.load()
        assertFalse(state.isLoaded)
        assertTrue(state.conversations.isEmpty())
        assertTrue(state.failureMessage != null)
        repository.failReads = false
        state.load()
        assertTrue(state.isLoaded)
        repository.failWrites = true
        state.toggleConversationPinned(1L)
        runCurrent()
        assertFalse(state.conversations.single().isPinned)
        assertTrue(state.failureMessage != null)
        state.deleteConversation(1L)
        runCurrent()
        assertEquals(1L, state.conversations.single().id)
        repository.failWrites = false
        val pending = state.createPendingStandaloneConversation("Report")
        repository.failWrites = true
        assertFailsWith<IllegalStateException> { state.revealStandaloneConversation(pending.id) }
        assertTrue(state.floatingConversations.isEmpty())
        assertFailsWith<IllegalStateException> { state.discardPendingStandaloneConversation(pending.id) }
        repository.failWrites = false
        state.revealStandaloneConversation(pending.id)
        assertEquals(pending.id, state.floatingConversations.single().id)
        state.close()
    }

    @Test
    fun pendingStandaloneConversationIsHiddenUntilRevealed() = runTest {
        val repository = RecordingConversationHistoryRepository()
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())

        val pending = state.createPendingStandaloneConversation("Daily report")

        assertTrue(state.conversations.isEmpty())
        assertTrue(state.floatingConversations.isEmpty())
        assertEquals(
            listOf(Triple(pending.id, "Daily report", ConversationPresentation.PendingStandalone)),
            repository.createdConversations,
        )

        state.setPendingStandaloneResult(pending.id, "Build passed")
        state.appendPendingStandaloneResultMessage(pending.id)
        state.revealStandaloneConversation(pending.id)

        assertEquals(listOf(pending.id), state.floatingConversations.map { it.id })
        assertEquals(
            listOf(pending.id to ConversationPresentation.Floating),
            repository.presentationChanges,
        )
        assertEquals(listOf(pending.id to "Build passed"), repository.standaloneResults)
        assertEquals(listOf("Build passed"), pending.messages.map { it.content })
        assertEquals(
            listOf(RecordedMessage(pending.id, 1L, "Build passed")),
            repository.appendedMessages,
        )
        state.close()
    }

    @Test
    fun completedStandaloneConversationWithoutExplicitResultCanBeRevealed() = runTest {
        val repository = RecordingConversationHistoryRepository()
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())
        val pending = state.createPendingStandaloneConversation("Daily report")

        state.revealStandaloneConversation(pending.id)

        assertEquals(listOf(pending.id), state.floatingConversations.map { it.id })
        assertEquals(
            listOf(pending.id to ConversationPresentation.Floating),
            repository.presentationChanges,
        )
        assertTrue(repository.standaloneResults.isEmpty())
        state.close()
    }

    @Test
    fun restoresPendingStandaloneConversationsOnlyAfterResponseWasPersisted() = runTest {
        val completedWithResult = storedConversation(
            id = 21L,
            presentation = ConversationPresentation.PendingStandalone,
            standaloneResult = "Completed result",
            messages = listOf(storedMessage(21L, MessageRole.Assistant)),
        )
        val interrupted = storedConversation(
            id = 22L,
            presentation = ConversationPresentation.PendingStandalone,
            standaloneResult = "Premature result",
            messages = listOf(storedMessage(22L, MessageRole.User)),
        )
        val completedWithoutResult = storedConversation(
            id = 23L,
            presentation = ConversationPresentation.PendingStandalone,
            messages = listOf(storedMessage(23L, MessageRole.Assistant)),
        )
        val repository = RecordingConversationHistoryRepository(
            listOf(completedWithResult, interrupted, completedWithoutResult),
        )
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())

        state.load()

        assertEquals(listOf(21L, 23L), state.floatingConversations.map { it.id })
        assertEquals(
            listOf(
                21L to ConversationPresentation.Floating,
                23L to ConversationPresentation.Floating,
            ),
            repository.presentationChanges,
        )
        assertEquals(listOf(22L), repository.deletedIds)
        assertEquals(listOf("content", "Completed result"), state.floatingConversations.first().messages.map { it.content })
        assertEquals(
            listOf(RecordedMessage(21L, 2L, "Completed result")),
            repository.appendedMessages,
        )
        state.close()
    }

    @Test
    fun loadsFloatingConversationsOutsideRecentListAndCanPromoteThem() = runTest {
        val repository = RecordingConversationHistoryRepository(
            stored = listOf(
                storedConversation(1L, ConversationPresentation.Recent),
                storedConversation(2L, ConversationPresentation.Floating),
            ),
        )
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())

        state.load()
        state.promoteFloatingConversation(2L)
        runCurrent()

        assertEquals(listOf(2L, 1L), state.conversations.map { it.id })
        assertTrue(state.floatingConversations.isEmpty())
        assertEquals(2L, state.activeId)
        assertEquals(listOf(2L to ConversationPresentation.Recent), repository.presentationChanges)
        state.close()
    }

    @Test
    fun discardingFloatingConversationSoftDeletesIt() = runTest {
        val repository = RecordingConversationHistoryRepository(
            stored = listOf(storedConversation(9L, ConversationPresentation.Floating)),
        )
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())

        state.load()
        state.discardFloatingConversation(9L)
        runCurrent()

        assertTrue(state.floatingConversations.isEmpty())
        assertEquals(listOf(9L), repository.deletedIds)
        state.close()
    }

    @Test
    fun toggleConversationPinnedPinsAnUnpinnedConversation() = runTest {
        val repository = RecordingConversationHistoryRepository()
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())
        state.conversations += HistoryConversationState(id = 1L, initialTitle = "First")
        state.conversations += HistoryConversationState(id = 2L, initialTitle = "Second")

        state.toggleConversationPinned(2L)
        runCurrent()

        assertEquals(listOf(2L, 1L), state.conversations.map { it.id })
        assertTrue(state.conversations.first().isPinned)
        assertEquals(listOf(2L to true), repository.pinChanges)
        state.close()
    }

    @Test
    fun toggleConversationPinnedUnpinsAPinnedConversation() = runTest {
        val repository = RecordingConversationHistoryRepository()
        val state = HistoryConversationSession(repository, this, EnvelopeChatMessageCodec())
        state.conversations += HistoryConversationState(id = 1L, initialTitle = "First", initialPinned = true)
        state.conversations += HistoryConversationState(id = 2L, initialTitle = "Second", initialPinned = true)
        state.conversations += HistoryConversationState(id = 3L, initialTitle = "Third")

        state.toggleConversationPinned(1L)
        runCurrent()

        assertEquals(listOf(2L, 1L, 3L), state.conversations.map { it.id })
        assertFalse(state.conversations[1].isPinned)
        assertEquals(listOf(1L to false), repository.pinChanges)
        state.close()
    }
}

private fun storedConversation(
    id: Long,
    presentation: ConversationPresentation,
    standaloneResult: String? = null,
    messages: List<StoredMessage> = emptyList(),
) = StoredConversation(
    id = id,
    title = "Conversation $id",
    createdAt = id,
    updatedAt = id,
    presentation = presentation,
    standaloneResult = standaloneResult,
    messages = messages,
)

private fun storedMessage(conversationId: Long, role: MessageRole) = StoredMessage(
    id = 1L,
    conversationId = conversationId,
    role = role.name,
    content = "content",
    isError = false,
    createdAt = 1L,
)

private class RecordingConversationHistoryRepository(
    private val stored: List<StoredConversation> = emptyList(),
) : ConversationHistoryRepository by EmptyHistoryFixture() {
    var failReads = false
    var failWrites = false
    val pinChanges = mutableListOf<Pair<Long, Boolean>>()
    val presentationChanges = mutableListOf<Pair<Long, ConversationPresentation>>()
    val deletedIds = mutableListOf<Long>()
    val createdConversations = mutableListOf<Triple<Long, String, ConversationPresentation>>()
    val standaloneResults = mutableListOf<Pair<Long, String>>()
    val appendedMessages = mutableListOf<RecordedMessage>()

    override suspend fun nextConversationId(): Long = maxOf(
        stored.maxOfOrNull { it.id } ?: 0L,
        createdConversations.maxOfOrNull { it.first } ?: 0L,
    ) + 1L

    override suspend fun loadAll(): List<StoredConversation> {
        check(!failReads) { "read failed" }
        return stored
    }

    override suspend fun appendMessage(
        conversationId: Long,
        title: String,
        messageId: Long,
        role: String,
        content: String,
        isError: Boolean,
    ) {
        appendedMessages += RecordedMessage(conversationId, messageId, content)
    }

    override suspend fun deleteMessagesFrom(conversationId: Long, messageIdInclusive: Long) = Unit

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) {
        check(!failWrites) { "write failed" }
        pinChanges += conversationId to pinned
    }

    override suspend fun setGoal(conversationId: Long, title: String, goal: ThreadGoal) = Unit

    override suspend fun clearGoal(conversationId: Long) = Unit

    override suspend fun deleteConversation(conversationId: Long) {
        check(!failWrites) { "write failed" }
        deletedIds += conversationId
    }

    override suspend fun createConversation(
        conversationId: Long,
        title: String,
        presentation: ConversationPresentation,
    ) {
        createdConversations += Triple(conversationId, title, presentation)
    }

    override suspend fun setConversationPresentation(
        conversationId: Long,
        presentation: ConversationPresentation,
    ) {
        check(!failWrites) { "write failed" }
        presentationChanges += conversationId to presentation
    }

    override suspend fun setStandaloneResult(conversationId: Long, result: String) {
        standaloneResults += conversationId to result
    }
}

private data class RecordedMessage(
    val conversationId: Long,
    val messageId: Long,
    val content: String,
)
