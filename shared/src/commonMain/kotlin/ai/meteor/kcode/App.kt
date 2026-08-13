@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.EmptyArtifactRepository
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ForegroundScheduledTaskPlatformHost
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
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
import androidx.compose.runtime.remember

@Composable
fun KcodeApp(
    chatService: ChatService,
    generationRunner: ChatGenerationRunner? = null,
    webContainerController: WebContainerController? = null,
    artifactRepository: ArtifactRepository = EmptyArtifactRepository,
    settingsStore: AppSettingsStore = TransientAppSettingsStore,
    historyRepository: ConversationHistoryRepository = TransientConversationHistoryRepository,
    imageSaver: ConversationImageSaver = UnsupportedConversationImageSaver,
    shellSettingsAvailable: Boolean = false,
    toolPermissionControlsAvailable: Boolean = false,
    scheduledTaskPlatformHost: ScheduledTaskPlatformHost = ForegroundScheduledTaskPlatformHost,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit = {},
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit = {},
) {
    val fallbackGenerationRunner = remember { ChatGenerationRunner() }
    val activeGenerationRunner = generationRunner ?: fallbackGenerationRunner
    KcodeTheme {
        KcodeMain(
            chatService,
            activeGenerationRunner,
            webContainerController,
            artifactRepository,
            settingsStore,
            historyRepository,
            imageSaver,
            shellSettingsAvailable,
            toolPermissionControlsAvailable,
            scheduledTaskPlatformHost,
            onShellExecutionModeChanged,
            onToolPermissionModeChanged,
        )
    }
}
