package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.plugin.AgentPluginManager

data class KcodeAgentRuntime(
    val chatService: ChatService,
    val conversationOverlayController: AgentConversationOverlayController? = null,
    val pluginManager: AgentPluginManager? = null,
    val owner: AgentRuntimeOwner? = null,
    val applicationContent: ApplicationContent? = null,
) {
    suspend fun close() {
        owner?.close()
    }

}

fun interface AgentRuntimeOwner {
    suspend fun close()
}
