package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationExportTest {
    @Test
    fun redactsCurrentAndHistoricalApiKeys() {
        val content = "current=provider-secret historical=sk_old_key_123"

        assertEquals(
            "current=•••• historical=••••",
            redactExportSecrets(content, "provider-secret"),
        )
    }

    @Test
    fun selectedExportKeepsOnlyChosenMessagesInConversationOrder() {
        val messages = listOf(
            ChatMessage(1, MessageRole.User, "first question"),
            ChatMessage(2, MessageRole.Assistant, "first answer"),
            ChatMessage(3, MessageRole.User, "second question"),
            ChatMessage(4, MessageRole.Assistant, "second answer"),
        )

        assertEquals(
            listOf(2L, 3L),
            messagesForExport(messages, setOf(3L, 2L)).map { it.id },
        )
        assertEquals(messages, messagesForExport(messages, null))
    }

}
