package app.kcode.model

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

/** Keeps the existing history schema compatible while persisting structured tool activity. */
fun ChatMessage.toStoredContent(): String {
    if (role != MessageRole.Assistant || toolUses.isEmpty()) return content
    val payload = PersistedAssistantMessage(
        text = content,
        toolUses = toolUses.map {
            PersistedToolUse(it.id, it.name, it.input, it.output, it.status.name, it.textOffset)
        },
    )
    return MESSAGE_ENVELOPE_PREFIX + messageJson.encodeToString(payload)
}

fun decodeStoredMessageContent(content: String): Pair<String, List<ToolUseInfo>> {
    if (!content.startsWith(MESSAGE_ENVELOPE_PREFIX)) return content to emptyList()
    return runCatching {
        val payload = messageJson.decodeFromString<PersistedAssistantMessage>(
            content.removePrefix(MESSAGE_ENVELOPE_PREFIX),
        )
        payload.text to payload.toolUses.map {
            ToolUseInfo(
                id = it.id,
                name = it.name,
                input = it.input,
                output = it.output,
                status = runCatching { ToolUseStatus.valueOf(it.status) }.getOrDefault(ToolUseStatus.Failed),
                textOffset = it.textOffset.coerceIn(0, payload.text.length),
            )
        }
    }.getOrElse { content.removePrefix(MESSAGE_ENVELOPE_PREFIX) to emptyList() }
}
