package app.kcode

import app.kcode.model.ChatMessage
import app.kcode.model.MessageRole
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

    @Test
    fun sentenceSelectionSupportsChineseAndEnglishPunctuation() {
        val text = "第一句。第二句包含重点！ Third sentence. Final one?"

        assertEquals("第二句包含重点！", text.substring(sentenceSelectionRange(text, 7)))
        assertEquals("Third sentence.", text.substring(sentenceSelectionRange(text, 18)))
        assertEquals("Final one?", text.substring(sentenceSelectionRange(text, text.lastIndex)))
    }

    @Test
    fun sentenceSelectionKeepsTechnicalDotsInsideSentence() {
        val text = "java.security.cert.CertPathValidatorException: failed. Version 5.6 is current."

        assertEquals(
            "java.security.cert.CertPathValidatorException: failed.",
            text.substring(sentenceSelectionRange(text, text.indexOf("cert"))),
        )
        assertEquals(
            "Version 5.6 is current.",
            text.substring(sentenceSelectionRange(text, text.indexOf("5.6"))),
        )
    }

    private fun String.substring(range: androidx.compose.ui.text.TextRange): String =
        substring(minOf(range.start, range.end), maxOf(range.start, range.end))
}
