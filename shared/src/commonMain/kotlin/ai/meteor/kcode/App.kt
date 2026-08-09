@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.UnsupportedConversationImageSaver
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.TransientConversationHistoryRepository
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.settings.TransientAppSettingsStore
import ai.meteor.kcode.ui.design.KcodeTheme
import ai.meteor.kcode.ui.pages.KcodeMain
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable

@Composable
fun KcodeApp(
    chatService: ChatService,
    webContainerController: WebContainerController? = null,
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
            webContainerController,
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
