package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.ui.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.LocalConversationCommands
import ai.meteor.kcode.plugin.ui.api.LocalModelCatalog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Product orchestration belongs to the mounted application renderer. */
@Composable
internal fun KcodeApp(
    services: ApplicationViewServices,
    options: ApplicationHostOptions,
    settingsOwner: PluginOperationOwner,
) {
    val sessions = services.conversationSessions ?: return
    val modelSettings = services.modelSettingsPolicy ?: return
    val generationRunner = options.generationRunner ?: services.generationRunner ?: return
    CompositionLocalProvider(
        LocalApplicationUiSlots provides services.uiSlots,
        LocalModelCatalog provides services.modelCatalog,
        LocalConversationCommands provides services.commands,
    ) {
        services.uiSlots.theme?.Render {
            KcodeMain(
                chatService = services.chatService,
                generationRunner = generationRunner,
                webContainerController = services.webContainerController,
                artifactRepository = services.artifactRepository,
                settingsStore = services.settingsStore,
                historyRepository = services.historyRepository,
                shellSettingsAvailable = options.shellSettingsAvailable,
                toolPermissionControlsAvailable = options.toolPermissionControlsAvailable,
                onShellExecutionModeChanged = options.onShellExecutionModeChanged,
                onToolPermissionModeChanged = options.onToolPermissionModeChanged,
                uiSlots = services.uiSlots,
                goalSessionFactory = services.goalSessions,
                scheduledTaskCoordinator = services.schedules,
                conversationSessionFactory = sessions,
                conversationExecution = services.conversationExecution,
                conversationExporter = services.conversationExporter,
                settingsOwner = settingsOwner,
                modelSettings = modelSettings,
            )
        }
    }
}
