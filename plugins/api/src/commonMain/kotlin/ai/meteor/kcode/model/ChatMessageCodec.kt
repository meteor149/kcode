package ai.meteor.kcode.model

/** Stored content formats belong to the active codec provider, not the shared model. */
interface ChatMessageCodec {
    suspend fun encode(message: ChatMessage): String
    suspend fun decode(content: String): DecodedStoredMessageContent
}

data class DecodedStoredMessageContent(
    val text: String,
    val toolUses: List<ToolUseInfo>,
    val subAgents: List<SubAgentInfo>,
)
