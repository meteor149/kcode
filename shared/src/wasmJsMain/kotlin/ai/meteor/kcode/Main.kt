package ai.meteor.kcode

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ai.meteor.kcode.settings.WebAppSettingsStore
import ai.meteor.kcode.history.createWebConversationHistoryRepository
import ai.meteor.kcode.artifact.createWebArtifactRepository

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val historyRepository = createWebConversationHistoryRepository()
    val permissionState = WebToolPermissionState()
    val runtime = createWebKoogChatRuntime(WebAppSettingsStore, permissionState)
    ComposeViewport(viewportContainerId = "webApp") {
        KcodeApp(
            chatService = runtime.chatService,
            webContainerController = runtime.webContainerController,
            artifactRepository = createWebArtifactRepository(),
            settingsStore = WebAppSettingsStore,
            historyRepository = historyRepository,
            toolPermissionControlsAvailable = true,
            onToolPermissionModeChanged = { permissionState.mode = it },
        )
    }
}
