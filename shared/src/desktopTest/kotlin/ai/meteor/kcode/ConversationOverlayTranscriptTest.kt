package ai.meteor.kcode

import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ConversationOverlayTranscriptTest {
    @Test
    fun transcriptIncludesConversationAndLiveActivity() {
        val transcript = ConversationOverlayTranscript(
            history = listOf(
                ChatMessage(1, MessageRole.User, "Earlier question"),
                ChatMessage(2, MessageRole.Assistant, "Earlier answer"),
            ),
            prompt = "Open another app",
        )
        transcript.appendResponse("**Working** on it")
        transcript.apply(ToolUseEvent.Started("tool-1", "tap", "button=Continue"))
        transcript.apply(ToolUseEvent.Finished("tool-1", "Tapped", isError = false))
        transcript.apply(SubAgentEvent.Spawned("/root/helper", "/root", "helper", "Inspect screen"))
        transcript.apply(
            SubAgentEvent.StatusChanged(
                path = "/root/helper",
                status = SubAgentStatus.Running,
                currentTool = "screenshot",
            ),
        )

        val messages = transcript.snapshot()
        val assistant = messages.last()

        assertEquals(
            listOf("Earlier question", "Earlier answer", "Open another app", "**Working** on it"),
            messages.map(ChatMessage::content),
        )
        assertEquals("tap", assistant.toolUses.single().name)
        assertEquals(ai.meteor.kcode.model.ToolUseStatus.Succeeded, assistant.toolUses.single().status)
        assertEquals("Tapped", assistant.toolUses.single().output)
        assertEquals(ai.meteor.kcode.model.SubAgentRunStatus.Running, assistant.subAgents.single().status)
        assertEquals("screenshot", assistant.subAgents.single().currentTool)
    }

    @Test
    fun permissionWaitChecksEveryIntervalAndStopsAfterFifthFailure() = runBlocking {
        var checks = 0
        val waits = mutableListOf<Long>()

        val granted = waitForConversationOverlayPermission(
            hasPermission = {
                checks += 1
                false
            },
            wait = waits::add,
        )

        assertEquals(false, granted)
        assertEquals(5, checks)
        assertEquals(List(5) { 5_000L }, waits)
    }

    @Test
    fun permissionWaitReturnsAtFirstSuccessfulCheck() = runBlocking {
        var checks = 0
        val waits = mutableListOf<Long>()

        val granted = waitForConversationOverlayPermission(
            hasPermission = {
                checks += 1
                checks == 3
            },
            wait = waits::add,
        )

        assertEquals(true, granted)
        assertEquals(3, checks)
        assertEquals(List(3) { 5_000L }, waits)
    }
}
