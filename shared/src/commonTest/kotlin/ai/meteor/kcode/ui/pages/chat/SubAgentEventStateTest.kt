package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.SubAgentRunStatus
import ai.meteor.kcode.ui.state.ConversationState
import kotlin.test.Test
import kotlin.test.assertEquals

class SubAgentEventStateTest {
    @Test
    fun projectsSubAgentLifecycleIntoTheStreamingAssistantMessage() {
        val state = ConversationState(1, "test")
        state.messages += ChatMessage(2, MessageRole.Assistant, "I will delegate.")

        state.applySubAgentEvent(
            2,
            SubAgentEvent.Spawned(
                path = "/root/research",
                parentPath = "/root",
                taskName = "research",
                prompt = "Inspect the source",
            ),
        )
        state.applySubAgentEvent(
            2,
            SubAgentEvent.StatusChanged(
                path = "/root/research",
                status = SubAgentStatus.Running,
                currentTool = "read_file",
            ),
        )
        state.applySubAgentEvent(
            2,
            SubAgentEvent.StatusChanged(
                path = "/root/research",
                status = SubAgentStatus.Completed,
                output = "Found it",
            ),
        )

        val agent = state.messages.single().subAgents.single()
        assertEquals("/root/research", agent.path)
        assertEquals(SubAgentRunStatus.Completed, agent.status)
        assertEquals("Found it", agent.output)
        assertEquals("I will delegate.".length, agent.textOffset)
    }
}
