@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages

import ai.meteor.kcode.ui.design.Paper

import ai.meteor.kcode.chat.ChatService

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalAppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ai.meteor.kcode.ui.component.BoxWithResponsiveWidth
import ai.meteor.kcode.ui.pages.chat.ChatPane
import ai.meteor.kcode.ui.state.rememberConversationSessionState
import ai.meteor.kcode.ui.pages.setting.PersistenceFailure
import ai.meteor.kcode.ui.pages.setting.SettingsPageOverlay
import ai.meteor.kcode.ui.pages.setting.toModelConfiguration
import ai.meteor.kcode.ui.pages.setting.withConfiguration
import ai.meteor.kcode.ui.pages.sidebar.SidebarScaffold
import kotlinx.coroutines.launch

@Composable
internal fun KcodeMain(
    chatService: ChatService,
    settingsStore: AppSettingsStore,
    historyRepository: ConversationHistoryRepository,
    imageSaver: ConversationImageSaver,
    shellSettingsAvailable: Boolean,
    toolPermissionControlsAvailable: Boolean,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit,
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val conversationSession = rememberConversationSessionState(historyRepository)
    var sidebarOpen by remember { mutableStateOf(false) }
    var appSettings by remember { mutableStateOf(StoredAppSettings()) }
    var configuration by remember { mutableStateOf<ModelConfiguration?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var persistenceFailure by remember { mutableStateOf<PersistenceFailure?>(null) }
    var settingsRevision by remember { mutableStateOf(0L) }

    LaunchedEffect(settingsStore) {
        val revisionAtLoadStart = settingsRevision
        runCatching { settingsStore.load() }
            .onSuccess { stored ->
                // Do not let a slow startup read replace settings the user has just saved.
                if (settingsRevision == revisionAtLoadStart) {
                    appSettings = stored
                    configuration = stored.toModelConfiguration()
                    onShellExecutionModeChanged(ShellExecutionMode.fromCode(stored.shellExecutionMode))
                    onToolPermissionModeChanged(ToolPermissionMode.fromCode(stored.toolPermissionMode))
                }
            }
            .onFailure { persistenceFailure = PersistenceFailure(reading = true, it.message) }
    }

    fun updateSettings(value: StoredAppSettings) {
        settingsRevision += 1L
        appSettings = value
        configuration = value.toModelConfiguration()
        persistenceFailure = null
        scope.launch {
            runCatching { settingsStore.save(value) }
                .onFailure { persistenceFailure = PersistenceFailure(reading = false, it.message) }
        }
    }

    fun updateConfiguration(value: ModelConfiguration) = updateSettings(appSettings.withConfiguration(value))

    fun newConversation() {
        conversationSession.startNewConversation()
        sidebarOpen = false
        settingsOpen = false
    }

    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.fromCode(appSettings.language)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .safeDrawingPadding(),
        ) {
            BoxWithResponsiveWidth { width ->
                val mainContent: @Composable (Modifier, Boolean) -> Unit =
                    { contentModifier, isCompact ->
                        val active = conversationSession.conversations
                            .firstOrNull { it.id == conversationSession.activeId }
                        ChatPane(
                            modifier = contentModifier,
                            compact = isCompact,
                            conversation = active,
                            service = chatService,
                            configuration = configuration,
                            onConfigurationChange = ::updateConfiguration,
                            onMenu = { sidebarOpen = true },
                            onSettings = { settingsOpen = true },
                            onNewConversation = ::newConversation,
                            onSendToNew = conversationSession::ensureConversation,
                            historyRepository = historyRepository,
                            imageSaver = imageSaver,
                            toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                            toolPermissionMode = ToolPermissionMode.fromCode(appSettings.toolPermissionMode),
                            onToolPermissionModeChange = {
                                onToolPermissionModeChanged(it)
                                updateSettings(appSettings.copy(toolPermissionMode = it.code))
                            },
                        )
                    }

                SidebarScaffold(
                    width = width,
                    sidebarOpen = sidebarOpen,
                    conversations = conversationSession.conversations,
                    activeId = conversationSession.activeId,
                    onSidebarOpenChange = { sidebarOpen = it },
                    onNew = ::newConversation,
                    onSelect = conversationSession::selectConversation,
                    onPin = conversationSession::pinConversation,
                    onDelete = conversationSession::deleteConversation,
                    onSettings = { settingsOpen = true },
                    content = mainContent,
                )
            }
            if (settingsOpen) {
                SettingsPageOverlay(
                    current = configuration,
                    appSettings = appSettings,
                    persistenceFailure = persistenceFailure,
                    shellSettingsAvailable = shellSettingsAvailable,
                    onSettingsChange = ::updateSettings,
                    onConfigurationChange = ::updateConfiguration,
                    onShellExecutionModeChanged = onShellExecutionModeChanged,
                    onDismiss = { settingsOpen = false },
                )
            }
        }
    }
}
