@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.plugin.ui.api.LocalModelCatalog
import ai.meteor.kcode.ui.design.Paper

import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ScheduledTaskCoordinator

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.localization.LocalizationContext
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ai.meteor.kcode.ui.component.BoxWithResponsiveWidth
import ai.meteor.kcode.ui.component.kcodeHazeSource
import ai.meteor.kcode.ui.component.rememberKcodeHazeState
import ai.meteor.kcode.plugin.ui.api.WebContainersOverlayRequest
import ai.meteor.kcode.plugin.ui.api.StandaloneConversationRequest
import ai.meteor.kcode.chat.ConversationSessionFactory
import androidx.compose.runtime.DisposableEffect
import ai.meteor.kcode.settings.ModelSettingsPolicy
import ai.meteor.kcode.plugin.ui.api.RenderApplicationLayout
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import ai.meteor.kcode.plugin.ui.api.NavigationPageRequest
import ai.meteor.kcode.plugin.ui.api.resolveNavigationDestination
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.plugin.ui.api.ApplicationEffectRequest
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.ArtifactsPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.launch

@Composable
internal fun KcodeMain(
    chatService: ChatService,
    generationRunner: ChatGenerationRunner,
    webContainerController: WebContainerController?,
    artifactRepository: ArtifactRepository,
    settingsStore: AppSettingsStore,
    historyRepository: ConversationHistoryRepository,
    shellSettingsAvailable: Boolean,
    toolPermissionControlsAvailable: Boolean,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit,
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit,
    uiSlots: ApplicationUiSlots,
    goalSessionFactory: GoalSessionFactory,
    scheduledTaskCoordinator: ScheduledTaskCoordinator,
    conversationSessionFactory: ConversationSessionFactory,
    conversationExecution: ConversationExecution?,
    conversationExporter: ConversationExporter?,
    settingsOwner: PluginOperationOwner,
    modelSettings: ModelSettingsPolicy,
) {
    val scope = rememberCoroutineScope()
    val conversationSession = remember(conversationSessionFactory) { conversationSessionFactory.create(scope) }
    DisposableEffect(conversationSession) {
        onDispose {
            conversationSession.cancel()
            scope.launch(NonCancellable) { conversationSession.close() }
        }
    }
    LaunchedEffect(conversationSession) { conversationSession.load() }
    var sidebarOpen by remember { mutableStateOf(false) }
    var selectedDestination by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = uiSlots.navigation.filter { it.isAvailable(uiSlots) }
    val destination = resolveNavigationDestination(selectedDestination, navigation)
    val conversationDestination = navigation.firstOrNull { it.handlesConversations }?.id
    LaunchedEffect(destination) { selectedDestination = destination }
    val settingsSession = remember(settingsStore, settingsOwner) { ApplicationSettingsSession(settingsStore, settingsOwner) }
    val appSettings = settingsSession.committed
    val configuration = modelSettings.resolve(appSettings, LocalModelCatalog.current)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    fun settingsCommitted(stored: StoredAppSettings) {
        onShellExecutionModeChanged(ShellExecutionMode.fromCode(stored.shellExecutionMode) ?: ShellExecutionMode.App)
        onToolPermissionModeChanged(ToolPermissionMode.fromCode(stored.toolPermissionMode) ?: ToolPermissionMode.Ask)
    }

    LaunchedEffect(settingsSession) { settingsSession.load(::settingsCommitted) }

    fun updateSettings(value: StoredAppSettings) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settingsSession.save(value, ::settingsCommitted) }
    }

    fun updateConfiguration(value: ModelConfiguration) = updateSettings(modelSettings.update(settingsSession.draft, value))

    fun updateModelSettings(value: ModelConfiguration, apiKeys: Map<String, String>) {
        updateSettings(modelSettings.update(settingsSession.draft.copy(modelApiKeys = apiKeys), value))
    }

    fun newConversation() {
        conversationSession.startNewConversation()
        sidebarOpen = false
        settingsOpen = false
        selectedDestination = conversationDestination
    }

    LocalizationContext(uiSlots.localization, appSettings.language) {
        val appLanguage = LocalAppLanguage.current
        uiSlots.effects.forEach { effect ->
            key(effect) {
                effect.renderer.Render(ApplicationEffectRequest(
                    conversationSession = conversationSession,
                    conversationExecution = conversationExecution,
                    configuration = configuration,
                    chatService = chatService,
                    generationRunner = generationRunner,
                    historyRepository = historyRepository,
                    goalSessionFactory = goalSessionFactory,
                    scheduledTaskCoordinator = scheduledTaskCoordinator,
                    language = appLanguage,
                ))
            }
        }
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
                            navigation.firstOrNull { it.id == destination }?.let { entry ->
                                val active = conversationSession.conversations.firstOrNull { it.id == conversationSession.activeId }
                                key(entry) {
                                    entry.renderer.Render(NavigationPageRequest(
                                        slots = uiSlots,
                                        conversationSession = conversationSession,
                                        chat = ChatPageRequest(
                                            modifier = contentModifier,
                                            compact = isCompact,
                                            conversation = active,
                                            conversationExecution = conversationExecution,
                                            service = chatService,
                                            generationRunner = generationRunner,
                                            configuration = configuration,
                                            onConfigurationChange = ::updateConfiguration,
                                            onMenu = { sidebarOpen = true },
                                            onSettings = { settingsOpen = true },
                                            onNewConversation = ::newConversation,
                                            onSendToNew = conversationSession::ensureConversation,
                                            historyRepository = historyRepository,
                                            goalSessionFactory = goalSessionFactory,
                                            scheduledTaskCoordinator = scheduledTaskCoordinator,
                                            conversationExporter = conversationExporter,
                                            toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                                            toolPermissionMode = ToolPermissionMode.fromCode(appSettings.toolPermissionMode) ?: ToolPermissionMode.Ask,
                                            onToolPermissionModeChange = {
                                                updateSettings(settingsSession.draft.copy(toolPermissionMode = it.code))
                                            },
                                        ),
                                        artifacts = ArtifactsPageRequest(
                                            repository = artifactRepository,
                                            webContainerController = webContainerController,
                                            compact = isCompact,
                                            onMenu = { sidebarOpen = true },
                                            modifier = contentModifier,
                                        ),
                                        onNavigate = { id -> if (navigation.any { it.id == id }) selectedDestination = id },
                                    ))
                                }
                            }
                        }

                    RenderApplicationLayout(
                        renderer = uiSlots.layout,
                        request = ApplicationLayoutRequest(
                            width = width,
                            sidebarOpen = sidebarOpen,
                            onSidebarOpenChange = { sidebarOpen = it },
                            sidebarRenderer = uiSlots.sidebar,
                            sidebar = SidebarPageRequest(
                                destination = destination,
                                navigation = navigation,
                                conversationActionsAvailable = conversationDestination != null,
                                settingsAvailable = uiSlots.settings != null,
                                conversations = conversationSession.conversations,
                                activeId = conversationSession.activeId,
                                onNew = ::newConversation,
                                onSelect = {
                                    conversationSession.selectConversation(it)
                                    selectedDestination = conversationDestination
                                },
                                onPin = conversationSession::toggleConversationPinned,
                                onDelete = conversationSession::deleteConversation,
                                onSettings = { settingsOpen = true },
                                onDestination = { selectedDestination = it },
                            ),
                            content = mainContent,
                        ),
                    )
                }
                if (settingsOpen) {
                    uiSlots.settings?.let { renderer ->
                        key(renderer) {
                            renderer.Render(
                                SettingsPageRequest(
                                    sections = uiSlots.settingsSections,
                                    modelCatalog = LocalModelCatalog.current,
                                    current = modelSettings.resolve(settingsSession.draft, LocalModelCatalog.current),
                                    appSettings = settingsSession.draft,
                                    persistenceFailure = settingsSession.failure,
                                    shellSettingsAvailable = shellSettingsAvailable,
                                    onSettingsChange = ::updateSettings,
                                    onModelSettingsChange = ::updateModelSettings,
                                    onDismiss = { settingsOpen = false },
                                ),
                            )
                        }
                    }
                }
            }
            uiSlots.webContainers?.let { renderer ->
                key(renderer) {
                    renderer.Render(WebContainersOverlayRequest(
                        hazeState = hazeState,
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    ))
                }
            }
            uiSlots.standaloneConversation?.let { renderer ->
                conversationSession.floatingConversations.firstOrNull()?.let { floating ->
                    key(renderer, floating.id) {
                        renderer.Render(StandaloneConversationRequest(
                            conversation = floating,
                            pendingCount = conversationSession.floatingConversations.size,
                            onAddToRecent = {
                                conversationSession.promoteFloatingConversation(floating.id)
                                selectedDestination = conversationDestination
                            },
                            onClose = { conversationSession.discardFloatingConversation(floating.id) },
                        ))
                    }
                }
            }
        }
    }
}
