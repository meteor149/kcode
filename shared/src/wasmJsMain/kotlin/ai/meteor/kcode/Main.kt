package ai.meteor.kcode

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ai.meteor.kcode.settings.WebAppSettingsStore
import ai.meteor.kcode.history.createWebConversationHistoryRepository

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val historyRepository = createWebConversationHistoryRepository()
    val permissionState = WebToolPermissionState()
    val chatService = createWebKoogChatService(WebAppSettingsStore, permissionState)
    ComposeViewport(viewportContainerId = "webApp") {
        KcodeApp(
            chatService = chatService,
            settingsStore = WebAppSettingsStore,
            historyRepository = historyRepository,
            toolPermissionControlsAvailable = true,
            onToolPermissionModeChanged = { permissionState.mode = it },
        )
    }
}
