package ai.meteor.kcode

import ai.meteor.kcode.webcontainer.WebContainerController

data class KcodeAgentRuntime(
    val chatService: KoogChatService,
    val webContainerController: WebContainerController,
)
