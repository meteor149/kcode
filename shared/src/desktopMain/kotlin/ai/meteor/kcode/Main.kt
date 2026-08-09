package ai.meteor.kcode

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ai.meteor.kcode.settings.createDesktopAppSettingsStore
import ai.meteor.kcode.history.createDesktopConversationHistoryRepository
import ai.meteor.kcode.export.DesktopConversationImageSaver

fun main() {
    val settingsStore = createDesktopAppSettingsStore()
    val historyRepository = createDesktopConversationHistoryRepository()
    application {
        val state = rememberWindowState(
            size = DpSize(1180.dp, 780.dp),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "kcode",
        ) {
            KcodeApp(
                chatService = createDesktopKoogChatService(settingsStore),
                settingsStore = settingsStore,
                historyRepository = historyRepository,
                imageSaver = DesktopConversationImageSaver(),
                toolPermissionControlsAvailable = true,
            )
        }
    }
}
