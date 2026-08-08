package app.kcode.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMessagePersistenceTest {
    @Test
    fun roundTripsToolTimelineWithoutChangingTheHistorySchema() {
        val original = ChatMessage(
            id = 7,
            role = MessageRole.Assistant,
            content = "I will inspect it.\nHere is the result.",
            toolUses = listOf(
                ToolUseInfo(
                    id = "call-1",
                    name = "read_file",
                    input = "{\"path\":\"notes.txt\"}",
                    output = "hello",
                    status = ToolUseStatus.Succeeded,
                    textOffset = 18,
                ),
            ),
        )

        val (text, toolUses) = decodeStoredMessageContent(original.toStoredContent())

        assertEquals(original.content, text)
        assertEquals(original.toolUses, toolUses)
    }

    @Test
    fun keepsLegacyPlainTextMessagesReadable() {
        val legacy = "ordinary assistant text"
        val (text, toolUses) = decodeStoredMessageContent(legacy)

        assertEquals(legacy, text)
        assertTrue(toolUses.isEmpty())
    }
}
