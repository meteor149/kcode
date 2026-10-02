package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ForegroundScheduledTaskPlatformHost
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.UnsupportedConversationImageSaver
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import androidx.compose.runtime.Composable

/** Platform bridges supplied by the window/activity; application capabilities come from plugins. */
data class ApplicationHostOptions(
    val generationRunner: ChatGenerationRunner? = null,
    val imageSaver: ConversationImageSaver = UnsupportedConversationImageSaver,
    val shellSettingsAvailable: Boolean = false,
    val toolPermissionControlsAvailable: Boolean = false,
    val scheduledTaskPlatformHost: ScheduledTaskPlatformHost = ForegroundScheduledTaskPlatformHost,
    val onShellExecutionModeChanged: (ShellExecutionMode) -> Unit = {},
    val onToolPermissionModeChanged: (ToolPermissionMode) -> Unit = {},
)

interface ApplicationContent {
    @Composable
    fun Render(options: ApplicationHostOptions)
}
