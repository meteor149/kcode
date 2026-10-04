package ai.meteor.kcode.model

enum class MessageRole { User, Assistant }

enum class ToolUseStatus { Running, Succeeded, Failed }

enum class SubAgentRunStatus { Pending, Running, Waiting, Completed, Failed, Interrupted }

data class SubAgentInfo(
    val path: String,
    val parentPath: String,
    val taskName: String,
    val prompt: String,
    val status: SubAgentRunStatus = SubAgentRunStatus.Pending,
    val currentTool: String? = null,
    val output: String = "",
    val textOffset: Int = 0,
)

data class ToolUseInfo(
    val id: String,
    val name: String,
    val input: String,
    val output: String = "",
    val status: ToolUseStatus = ToolUseStatus.Running,
    /** Character offset in [ChatMessage.content] at which this call was emitted. */
    val textOffset: Int = 0,
)

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val isError: Boolean = false,
    val toolUses: List<ToolUseInfo> = emptyList(),
    val subAgents: List<SubAgentInfo> = emptyList(),
)

data class Conversation(
    val id: Long,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
)
