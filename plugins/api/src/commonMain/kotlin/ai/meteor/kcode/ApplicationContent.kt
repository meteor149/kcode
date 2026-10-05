package ai.meteor.kcode

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import androidx.compose.runtime.Composable

/** Platform bridges supplied by the window/activity; application capabilities come from plugins. */
data class ApplicationHostOptions(
    val generationRunner: ChatGenerationRunner? = null,
    val shellSettingsAvailable: Boolean = false,
    val conversationSettingsControlsAvailable: Boolean = false,
    val onShellExecutionModeChanged: (ShellExecutionMode) -> Unit = {},
    val onToolPermissionModeChanged: (ToolPermissionMode) -> Unit = {},
)

interface ApplicationContent {
    suspend fun updateSettings(update: SettingsUpdate): AppliedSettingsUpdate =
        error("Settings commands are unavailable")

    suspend fun modelCatalog(): ModelCatalogSnapshot = ModelCatalogSnapshot()

    @Composable
    fun Render(options: ApplicationHostOptions)
}
