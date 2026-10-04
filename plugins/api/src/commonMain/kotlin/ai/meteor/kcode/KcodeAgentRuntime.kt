package ai.meteor.kcode

import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.plugin.AgentPluginManager
import ai.meteor.kcode.webcontainer.WebContainerController

data class KcodeAgentRuntime(
    val chatService: ChatService,
    val webContainerController: WebContainerController,
    val artifactRepository: ArtifactRepository,
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
