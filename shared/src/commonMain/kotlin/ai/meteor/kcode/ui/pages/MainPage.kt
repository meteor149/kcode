@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages

import ai.meteor.kcode.ui.design.Paper

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalAppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ai.meteor.kcode.ui.component.BoxWithResponsiveWidth
import ai.meteor.kcode.ui.component.kcodeHazeSource
import ai.meteor.kcode.ui.component.rememberKcodeHazeState
import ai.meteor.kcode.ui.component.WebBackgroundContainersOverlay
import ai.meteor.kcode.ui.pages.chat.ChatPane
import ai.meteor.kcode.ui.state.rememberConversationSessionState
import ai.meteor.kcode.ui.pages.setting.PersistenceFailure
import ai.meteor.kcode.ui.pages.setting.SettingsPageOverlay
import ai.meteor.kcode.ui.pages.setting.toModelConfiguration
import ai.meteor.kcode.ui.pages.setting.withConfiguration
import ai.meteor.kcode.ui.pages.sidebar.SidebarScaffold
import ai.meteor.kcode.ui.pages.sidebar.MainDestination
import ai.meteor.kcode.ui.pages.artifact.ArtifactsPage
import ai.meteor.kcode.artifact.ArtifactRepository
import kotlinx.coroutines.launch

@Composable
internal fun KcodeMain(
    chatService: ChatService,
    generationRunner: ChatGenerationRunner,
    webContainerController: WebContainerController?,
    artifactRepository: ArtifactRepository,
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
    var destination by rememberSaveable { mutableStateOf(MainDestination.Chat) }
    var appSettings by remember { mutableStateOf(StoredAppSettings()) }
    var configuration by remember { mutableStateOf<ModelConfiguration?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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

    fun updateModelSettings(value: ModelConfiguration, apiKeys: Map<String, String>) {
        updateSettings(
            appSettings.copy(modelApiKeys = apiKeys).withConfiguration(value),
        )
    }

    fun newConversation() {
        conversationSession.startNewConversation()
        sidebarOpen = false
        settingsOpen = false
        destination = MainDestination.Chat
    }

    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.fromCode(appSettings.language)) {
        val hazeState = rememberKcodeHazeState()
        Box(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                ),
        ) {
            Box(Modifier.fillMaxSize().kcodeHazeSource(hazeState)) {
                BoxWithResponsiveWidth { width ->
                    val mainContent: @Composable (Modifier, Boolean) -> Unit =
                        { contentModifier, isCompact ->
                            when (destination) {
                                MainDestination.Chat -> {
                                    val active = conversationSession.conversations
                                        .firstOrNull { it.id == conversationSession.activeId }
                                    ChatPane(
                                        modifier = contentModifier,
                                        compact = isCompact,
                                        conversation = active,
                                        service = chatService,
                                        generationRunner = generationRunner,
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
                                MainDestination.Artifacts -> ArtifactsPage(
                                    repository = artifactRepository,
                                    webContainerController = webContainerController,
                                    compact = isCompact,
                                    onMenu = { sidebarOpen = true },
                                    modifier = contentModifier,
                                )
                            }
                        }

                    SidebarScaffold(
                        width = width,
                        sidebarOpen = sidebarOpen,
                        destination = destination,
                        conversations = conversationSession.conversations,
                        activeId = conversationSession.activeId,
                        onSidebarOpenChange = { sidebarOpen = it },
                        onNew = ::newConversation,
                        onSelect = {
                            conversationSession.selectConversation(it)
                            destination = MainDestination.Chat
                        },
                        onPin = conversationSession::toggleConversationPinned,
                        onDelete = conversationSession::deleteConversation,
                        onSettings = { settingsOpen = true },
                        onDestination = { destination = it },
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
                        onModelSettingsChange = ::updateModelSettings,
                        onShellExecutionModeChanged = onShellExecutionModeChanged,
                        onDismiss = { settingsOpen = false },
                    )
                }
            }
            webContainerController?.let { controller ->
                WebBackgroundContainersOverlay(
                    controller = controller,
                    hazeState = hazeState,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                )
            }
        }
    }
}
