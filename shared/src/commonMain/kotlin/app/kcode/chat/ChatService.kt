package app.kcode.chat

import app.kcode.model.ChatMessage
import app.kcode.model.ModelConfiguration

sealed interface ToolUseEvent {
    val id: String

    data class Started(
        override val id: String,
        val name: String,
        val input: String,
    ) : ToolUseEvent

    data class Finished(
        override val id: String,
        val output: String,
        val isError: Boolean,
    ) : ToolUseEvent
}

interface ChatService {
    val availability: ChatAvailability?
    suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String

    /**
     * Produces a reply while forwarding provider text deltas as soon as they arrive.
     * Gateways that do not expose streaming yet retain the same contract via the fallback.
     */
    suspend fun replyStreaming(
        configuration: ModelConfiguration,
        history: List<ChatMessage>,
        prompt: String,
        onToolUse: suspend (ToolUseEvent) -> Unit = {},
        onDelta: suspend (String) -> Unit,
    ): String = reply(configuration, history, prompt).also { onDelta(it) }
}

enum class ChatAvailability { BrowserGateway, IosGateway }

class ChatServiceUnavailable(val availability: ChatAvailability) : Exception()
