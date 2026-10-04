package ai.meteor.kcode.session

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoryConversationStateTest {
    @Test
    fun concurrentPendingCommandsReserveDistinctRangesAroundRestoredMessages() = runTest {
        val conversation = HistoryConversationState(1, "restored")
        conversation.messages += ChatMessage(40L, MessageRole.User, "saved")
        val reservations = (1..100).map {
            async(Dispatchers.Default) { conversation.reserveMessageIds(2) }
        }.awaitAll()
        assertEquals((41L..240L step 2).toSet(), reservations.toSet())
        conversation.messages += ChatMessage(300L, MessageRole.Assistant, "imported")
        assertEquals(301L, conversation.reserveMessageIds())
    }

    @Test
    fun exhaustedMessageIdsCannotWrapIntoExistingTranscriptIds() {
        val conversation = HistoryConversationState(1, "full")
        conversation.messages += ChatMessage(Long.MAX_VALUE - 1, MessageRole.User, "saved")
        assertFailsWith<IllegalStateException> { conversation.reserveMessageIds(2) }
        assertEquals(Long.MAX_VALUE, conversation.reserveMessageIds())
        assertFailsWith<IllegalStateException> { conversation.reserveMessageIds() }
    }
}
