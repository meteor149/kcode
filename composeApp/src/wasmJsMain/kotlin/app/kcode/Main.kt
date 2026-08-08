package app.kcode

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.kcode.settings.WebAppSettingsStore
import app.kcode.history.createWebConversationHistoryRepository

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
