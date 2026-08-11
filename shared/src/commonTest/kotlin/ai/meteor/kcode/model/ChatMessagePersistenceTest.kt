package ai.meteor.kcode.model

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

        val decoded = decodeStoredMessageContent(original.toStoredContent())

        assertEquals(original.content, decoded.text)
        assertEquals(original.toolUses, decoded.toolUses)
    }

    @Test
    fun keepsLegacyPlainTextMessagesReadable() {
        val legacy = "ordinary assistant text"
        val decoded = decodeStoredMessageContent(legacy)

        assertEquals(legacy, decoded.text)
        assertTrue(decoded.toolUses.isEmpty())
    }

    @Test
    fun roundTripsSubAgentRuntimeState() {
        val original = ChatMessage(
            id = 9,
            role = MessageRole.Assistant,
            content = "Delegating now.",
            subAgents = listOf(
                SubAgentInfo(
                    path = "/root/research",
                    parentPath = "/root",
                    taskName = "research",
                    prompt = "Inspect the source",
                    status = SubAgentRunStatus.Completed,
                    output = "Found the implementation.",
                    textOffset = 15,
                ),
            ),
        )

        val decoded = decodeStoredMessageContent(original.toStoredContent())

        assertEquals(original.content, decoded.text)
        assertEquals(original.subAgents, decoded.subAgents)
    }
}
