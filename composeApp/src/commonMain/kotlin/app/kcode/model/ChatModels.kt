package app.kcode.model

enum class MessageRole { User, Assistant }

enum class ToolUseStatus { Running, Succeeded, Failed }

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
)

data class Conversation(
    val id: Long,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
)

fun conversationTitle(prompt: String): String {
    val normalized = prompt.trim().replace(Regex("\\s+"), " ")
    return when {
        normalized.isBlank() -> ""
        normalized.length <= 24 -> normalized
        else -> normalized.take(24).trimEnd() + "…"
    }
}

fun buildContext(messages: List<ChatMessage>, latestPrompt: String): String = buildString {
    appendLine("下面是当前对话。请延续上下文，直接回答最后一条用户消息。")
    messages.filterNot { it.isError }.forEach { message ->
        val role = if (message.role == MessageRole.User) "用户" else "助手"
        appendLine("$role：${message.content}")
    }
    append("用户：$latestPrompt")
}
