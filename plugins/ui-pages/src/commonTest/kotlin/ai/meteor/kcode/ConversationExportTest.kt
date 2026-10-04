package ai.meteor.kcode

import ai.meteor.kcode.plugin.pages.chat.component.sentenceSelectionRange
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationExportTest {
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
