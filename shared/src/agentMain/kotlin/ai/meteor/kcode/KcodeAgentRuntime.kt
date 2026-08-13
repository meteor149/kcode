package ai.meteor.kcode

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.artifact.ArtifactRepository

data class KcodeAgentRuntime(
    val chatService: KoogChatService,
    val webContainerController: WebContainerController,
    val artifactRepository: ArtifactRepository,
    val conversationOverlayController: AgentConversationOverlayController? = null,
)
