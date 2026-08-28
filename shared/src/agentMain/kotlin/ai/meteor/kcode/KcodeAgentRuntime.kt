package ai.meteor.kcode

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.plugin.AgentPluginManager

data class KcodeAgentRuntime(
    val chatService: ChatService,
    val webContainerController: WebContainerController,
    val artifactRepository: ArtifactRepository,
    val conversationOverlayController: AgentConversationOverlayController? = null,
    val pluginManager: AgentPluginManager? = null,
    val owner: AgentRuntimeOwner? = null,
) {
    suspend fun close() {
        var failure: Throwable? = null
        try {
            conversationOverlayController?.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            owner?.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

fun interface AgentRuntimeOwner {
    suspend fun close()
}
