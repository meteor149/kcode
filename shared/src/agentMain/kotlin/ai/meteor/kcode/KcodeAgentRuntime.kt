package ai.meteor.kcode

import ai.meteor.kcode.h5.H5ContainerController

data class KcodeAgentRuntime(
    val chatService: KoogChatService,
    val h5ContainerController: H5ContainerController,
)
