@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode

import app.kcode.chat.ChatService
import app.kcode.export.ConversationImageSaver
import app.kcode.export.UnsupportedConversationImageSaver
import app.kcode.history.ConversationHistoryRepository
import app.kcode.history.TransientConversationHistoryRepository
import app.kcode.settings.AppSettingsStore
import app.kcode.settings.ShellExecutionMode
import app.kcode.settings.ToolPermissionMode
import app.kcode.settings.TransientAppSettingsStore
import app.kcode.ui.design.KcodeTheme
import app.kcode.ui.pages.KcodeMain
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable

@Composable
fun KcodeApp(
    chatService: ChatService,
    settingsStore: AppSettingsStore = TransientAppSettingsStore,
    historyRepository: ConversationHistoryRepository = TransientConversationHistoryRepository,
    imageSaver: ConversationImageSaver = UnsupportedConversationImageSaver,
    shellSettingsAvailable: Boolean = false,
    toolPermissionControlsAvailable: Boolean = false,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit = {},
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit = {},
) {
    KcodeTheme {
        KcodeMain(
            chatService,
            settingsStore,
            historyRepository,
            imageSaver,
            shellSettingsAvailable,
            toolPermissionControlsAvailable,
            onShellExecutionModeChanged,
            onToolPermissionModeChanged,
        )
    }
}
