package ai.meteor.kcode.chat

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration

sealed interface ToolUseEvent {
    val id: String

    data class Started(
        override val id: String,
        val name: String,
        val input: String,
    ) : ToolUseEvent

    data class Updated(
        override val id: String,
        val input: String,
    ) : ToolUseEvent

    data class Finished(
        override val id: String,
        val output: String,
        val isError: Boolean,
    ) : ToolUseEvent
}

enum class SubAgentStatus { Pending, Running, Waiting, Completed, Failed, Interrupted }

sealed interface SubAgentEvent {
    val path: String

    data class Spawned(
        override val path: String,
        val parentPath: String,
        val taskName: String,
        val prompt: String,
    ) : SubAgentEvent

    data class StatusChanged(
        override val path: String,
        val status: SubAgentStatus,
        val currentTool: String? = null,
        val output: String? = null,
    ) : SubAgentEvent
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
        onSubAgent: suspend (SubAgentEvent) -> Unit = {},
        onDelta: suspend (String) -> Unit,
    ): String = reply(configuration, history, prompt).also { onDelta(it) }
}

enum class ChatAvailability { BrowserGateway, IosGateway }

class ChatServiceUnavailable(val availability: ChatAvailability) : Exception()
