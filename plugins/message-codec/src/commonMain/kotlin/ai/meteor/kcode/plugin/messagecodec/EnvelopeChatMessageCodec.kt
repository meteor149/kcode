package ai.meteor.kcode.plugin.messagecodec

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.DecodedStoredMessageContent
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.model.SubAgentRunStatus
import ai.meteor.kcode.plugin.api.PluginOperationOwner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MESSAGE_ENVELOPE_PREFIX = "\u001ekcode-message-v1:"

private val messageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class PersistedAssistantMessage(
    val text: String,
    val toolUses: List<PersistedToolUse>,
    val subAgents: List<PersistedSubAgent> = emptyList(),
)

@Serializable
private data class PersistedToolUse(
    val id: String,
    val name: String,
    val input: String,
    val output: String,
    val status: String,
    val textOffset: Int,
)

@Serializable
private data class PersistedSubAgent(
    val path: String,
    val parentPath: String,
    val taskName: String,
    val prompt: String,
    val status: String,
    val currentTool: String?,
    val output: String,
    val textOffset: Int,
)

/** Keeps the existing history schema compatible while persisting structured tool activity. */
private fun ChatMessage.toStoredContent(): String {
    if (role != MessageRole.Assistant || (toolUses.isEmpty() && subAgents.isEmpty())) return content
    val payload = PersistedAssistantMessage(
        text = content,
        toolUses = toolUses.map {
            PersistedToolUse(it.id, it.name, it.input, it.output, it.status.name, it.textOffset)
        },
        subAgents = subAgents.map {
            PersistedSubAgent(
                it.path,
                it.parentPath,
                it.taskName,
                it.prompt,
                it.status.name,
                it.currentTool,
                it.output,
                it.textOffset,
            )
        },
    )
    return MESSAGE_ENVELOPE_PREFIX + messageJson.encodeToString(payload)
}

private fun decodeStoredMessageContent(content: String): DecodedStoredMessageContent {
    if (!content.startsWith(MESSAGE_ENVELOPE_PREFIX)) {
        return DecodedStoredMessageContent(content, emptyList(), emptyList())
    }
    return runCatching {
        val payload = messageJson.decodeFromString<PersistedAssistantMessage>(
            content.removePrefix(MESSAGE_ENVELOPE_PREFIX),
        )
        DecodedStoredMessageContent(
            text = payload.text,
            toolUses = payload.toolUses.map {
            ToolUseInfo(
                id = it.id,
                name = it.name,
                input = it.input,
                output = it.output,
                status = runCatching { ToolUseStatus.valueOf(it.status) }.getOrDefault(ToolUseStatus.Failed),
                textOffset = it.textOffset.coerceIn(0, payload.text.length),
            )
            },
            subAgents = payload.subAgents.map {
                SubAgentInfo(
                    path = it.path,
                    parentPath = it.parentPath,
                    taskName = it.taskName,
                    prompt = it.prompt,
                    status = runCatching { SubAgentRunStatus.valueOf(it.status) }
                        .getOrDefault(SubAgentRunStatus.Failed),
                    currentTool = it.currentTool,
                    output = it.output,
                    textOffset = it.textOffset.coerceIn(0, payload.text.length),
                )
            },
        )
    }.getOrElse {
        DecodedStoredMessageContent(content.removePrefix(MESSAGE_ENVELOPE_PREFIX), emptyList(), emptyList())
    }
}

/** Each mount owns its calls and refuses stale references after withdrawal. */
class EnvelopeChatMessageCodec : ChatMessageCodec {
    private val owner = PluginOperationOwner("Message codec provider")
    override suspend fun encode(message: ChatMessage): String = owner.run { message.toStoredContent() }
    override suspend fun decode(content: String): DecodedStoredMessageContent = owner.run { decodeStoredMessageContent(content) }
    suspend fun close() = owner.close()
}
