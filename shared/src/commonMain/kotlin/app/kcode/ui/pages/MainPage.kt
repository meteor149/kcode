@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode.ui.pages

import app.kcode.ui.design.Paper

import app.kcode.chat.ChatService

import app.kcode.model.ModelConfiguration
import app.kcode.history.ConversationHistoryRepository
import app.kcode.export.ConversationImageSaver
import app.kcode.settings.AppSettingsStore
import app.kcode.settings.StoredAppSettings
import app.kcode.settings.ShellExecutionMode
import app.kcode.settings.ToolPermissionMode
import app.kcode.localization.AppLanguage
import app.kcode.localization.LocalAppLanguage
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
import app.kcode.ui.component.BoxWithResponsiveWidth
import app.kcode.ui.pages.chat.ChatPane
import app.kcode.ui.state.rememberConversationSessionState
import app.kcode.ui.pages.setting.PersistenceFailure
import app.kcode.ui.pages.setting.SettingsPageOverlay
import app.kcode.ui.pages.setting.toModelConfiguration
import app.kcode.ui.pages.setting.withConfiguration
import app.kcode.ui.pages.sidebar.SidebarScaffold
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
