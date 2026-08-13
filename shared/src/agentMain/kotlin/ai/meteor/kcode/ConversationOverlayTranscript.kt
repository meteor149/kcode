package ai.meteor.kcode

import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.model.SubAgentRunStatus
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus

internal class ConversationOverlayTranscript(
    history: List<ChatMessage>,
    prompt: String,
) {
    private val previousMessages = history.toList()
    private val userMessage = ChatMessage(
        id = nextMessageId(previousMessages),
        role = MessageRole.User,
        content = prompt,
    )
    private val assistantMessageId = userMessage.id + 1L
    private val response = StringBuilder()
    private val toolCalls = linkedMapOf<String, ToolUseInfo>()
    private val subAgents = linkedMapOf<String, SubAgentInfo>()

    fun appendResponse(delta: String) {
        response.append(delta)
    }

    fun apply(event: ToolUseEvent) {
        when (event) {
            is ToolUseEvent.Started -> toolCalls[event.id] = ToolUseInfo(
                id = event.id,
                name = event.name,
                input = event.input,
                textOffset = response.length,
            )
            is ToolUseEvent.Updated -> toolCalls[event.id]?.let { current ->
                toolCalls[event.id] = current.copy(input = event.input)
            }
            is ToolUseEvent.Finished -> toolCalls[event.id]?.let { current ->
                toolCalls[event.id] = current.copy(
                    output = event.output,
                    status = if (event.isError) ToolUseStatus.Failed else ToolUseStatus.Succeeded,
                )
            }
        }
    }

    fun apply(event: SubAgentEvent) {
        when (event) {
            is SubAgentEvent.Spawned -> subAgents[event.path] = SubAgentInfo(
                path = event.path,
                parentPath = event.parentPath,
                taskName = event.taskName,
                prompt = event.prompt,
                textOffset = response.length,
            )
            is SubAgentEvent.StatusChanged -> subAgents[event.path]?.let { current ->
                subAgents[event.path] = current.copy(
                    status = event.status.toRunStatus(),
                    currentTool = event.currentTool,
                    output = event.output ?: current.output,
                )
            }
        }
    }

    fun snapshot(): List<ChatMessage> = buildList {
        addAll(previousMessages)
        add(userMessage)
        if (response.isNotEmpty() || toolCalls.isNotEmpty() || subAgents.isNotEmpty()) {
            add(
                ChatMessage(
                    id = assistantMessageId,
                    role = MessageRole.Assistant,
                    content = response.toString(),
                    toolUses = toolCalls.values.toList(),
                    subAgents = subAgents.values.toList(),
                ),
            )
        }
    }

    private fun nextMessageId(messages: List<ChatMessage>): Long {
        val greatest = messages.maxOfOrNull(ChatMessage::id) ?: 0L
        return if (greatest >= Long.MAX_VALUE - 1L) Long.MIN_VALUE else greatest + 1L
    }

    private fun SubAgentStatus.toRunStatus(): SubAgentRunStatus = when (this) {
        SubAgentStatus.Pending -> SubAgentRunStatus.Pending
        SubAgentStatus.Running -> SubAgentRunStatus.Running
        SubAgentStatus.Waiting -> SubAgentRunStatus.Waiting
        SubAgentStatus.Completed -> SubAgentRunStatus.Completed
        SubAgentStatus.Failed -> SubAgentRunStatus.Failed
        SubAgentStatus.Interrupted -> SubAgentRunStatus.Interrupted
    }
}
