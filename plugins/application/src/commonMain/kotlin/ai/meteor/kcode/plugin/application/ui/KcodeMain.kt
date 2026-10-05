@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.plugin.ui.api.SettingsEditorProjection
import ai.meteor.kcode.plugin.api.ToolPermissionSettingsPolicy
import ai.meteor.kcode.plugin.ui.api.LocalModelCatalog
import ai.meteor.kcode.ui.design.Paper

import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ScheduledTaskCoordinator

import ai.meteor.kcode.history.ConversationHistoryRepository
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
import ai.meteor.kcode.plugin.ui.api.StandaloneConversationRequest
import ai.meteor.kcode.chat.ConversationSessionFactory
import androidx.compose.runtime.DisposableEffect
import ai.meteor.kcode.settings.ModelSettingsPolicy
import ai.meteor.kcode.plugin.ui.api.RenderApplicationLayout
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import ai.meteor.kcode.plugin.ui.api.NavigationPageRequest
import ai.meteor.kcode.plugin.ui.api.resolveNavigationDestination
import ai.meteor.kcode.plugin.ui.api.ApplicationEffectRequest
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ShellModeSettingsPolicy
import kotlinx.coroutines.launch

@Composable
internal fun KcodeMain(
    chatService: ChatService,
    generationRunner: ChatGenerationRunner?,
    settingsStore: AppSettingsStore,
    hostOptions: ApplicationHostOptions,
    historyRepository: ConversationHistoryRepository?,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit,
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit,
    uiSlots: ApplicationUiSlots,
    goalSessionFactory: GoalSessionFactory,
    scheduledTaskCoordinator: ScheduledTaskCoordinator,
    conversationSessionFactory: ConversationSessionFactory?,
    conversationExecution: ConversationExecution?,
    settingsOwner: PluginOperationOwner,
    modelSettings: ModelSettingsPolicy?,
    shellModeSettings: ShellModeSettingsPolicy?,
    toolPermissionSettings: ToolPermissionSettingsPolicy? = null,
) {
    val scope = rememberCoroutineScope()
    val conversationSession = remember(conversationSessionFactory) { conversationSessionFactory?.create(scope) }
    DisposableEffect(conversationSession) {
        onDispose {
            conversationSession?.let { session ->
                session.cancel()
                scope.launch(NonCancellable) { session.close() }
            }
        }
    }
    LaunchedEffect(conversationSession) { conversationSession?.load() }
    var sidebarOpen by remember { mutableStateOf(false) }
    var selectedDestination by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = uiSlots.navigation.filter { it.isAvailable(uiSlots) }
    val destination = resolveNavigationDestination(selectedDestination, navigation)
    val conversationDestination = navigation.firstOrNull { it.handlesConversations }?.id
    val settingsSession = remember(settingsStore, settingsOwner) { ApplicationSettingsSession(settingsStore, settingsOwner) }
    val appSettings = settingsSession.committed
    val configuration = modelSettings?.resolve(appSettings, LocalModelCatalog.current)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    fun settingsCommitted(stored: StoredAppSettings) {
        shellModeSettings?.let { onShellExecutionModeChanged(it.resolve(stored)) }
        toolPermissionSettings?.let { onToolPermissionModeChanged(it.resolve(stored)) }
    }

    LaunchedEffect(settingsSession) { settingsSession.load(::settingsCommitted) }

    fun updateSettings(value: StoredAppSettings) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settingsSession.save(value, ::settingsCommitted) }
    }

    fun newConversation() {
        val session = conversationSession ?: return
        session.startNewConversation()
        sidebarOpen = false
        settingsOpen = false
        selectedDestination = conversationDestination
    }

    val textCatalog = remember(uiSlots.localization, uiSlots.textDictionaries) {
        ApplicationTextCatalog(uiSlots.localization, uiSlots.textDictionaries)
    }
    DisposableEffect(textCatalog) { onDispose { textCatalog.close() } }
    LocalizationContext(textCatalog,
        (textCatalog.languageSettings?.preferredLanguage(appSettings) ?: textCatalog.snapshot()?.defaultLanguage)?.code.orEmpty(),
    ) {
        val appLanguage = LocalAppLanguage.current
        if (generationRunner != null && conversationSession != null && historyRepository != null) {
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
                                key(entry.renderKey) {
                                    entry.renderer.Render(NavigationPageRequest(
                                        slots = uiSlots,
                                        conversationSession = conversationSession,
                                        modifier = contentModifier,
                                        compact = isCompact,
                                        settingsEditor = SettingsEditorProjection(settingsSession.draft, ::updateSettings),
                                        committedSettings = appSettings,
                                        hostOptions = hostOptions,
                                        onMenu = { sidebarOpen = true },
                                        onSettings = { settingsOpen = true },
                                        onNewConversation = ::newConversation,
                                        onNavigate = { id -> if (navigation.any { it.id == id }) selectedDestination = id },
                                    ))
                                }
                            }
                        }

                    RenderApplicationLayout(
                        renderer = uiSlots.layout,
                        request = ApplicationLayoutRequest(
                            width = width,
                            sidebarOpen = sidebarOpen || navigation.isEmpty() || (
                                (generationRunner == null || conversationSession == null || historyRepository == null) &&
                                    navigation.firstOrNull { it.id == destination }?.handlesConversations == true
                                ),
                            onSidebarOpenChange = { sidebarOpen = it },
                            sidebarRenderer = uiSlots.sidebar,
                            sidebar = SidebarPageRequest(
                                destination = destination,
                                navigation = navigation,
                                conversationActionsAvailable = conversationSession != null && conversationDestination != null,
                                settingsAvailable = uiSlots.settings != null,
                                conversations = conversationSession?.conversations.orEmpty(),
                                activeId = conversationSession?.activeId,
                                onNew = ::newConversation,
                                onSelect = {
                                    conversationSession?.selectConversation(it)
                                    selectedDestination = conversationDestination
                                },
                                onPin = { conversationSession?.toggleConversationPinned(it) },
                                onDelete = { conversationSession?.deleteConversation(it) },
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
                                    appSettings = settingsSession.draft,
                                    persistenceFailure = settingsSession.failure,
                                    onSettingsChange = ::updateSettings,
                                    onDismiss = { settingsOpen = false },
                                ),
                            )
                        }
                    }
                }
            }
            uiSlots.standaloneConversation?.let { renderer ->
                val session = conversationSession ?: return@let
                session.floatingConversations.firstOrNull()?.let { floating ->
                    key(renderer, floating.id) {
                        renderer.Render(StandaloneConversationRequest(
                            conversation = floating,
                            pendingCount = session.floatingConversations.size,
                            onAddToRecent = {
                                session.promoteFloatingConversation(floating.id)
                                selectedDestination = conversationDestination
                            },
                            onClose = { session.discardFloatingConversation(floating.id) },
                        ))
                    }
                }
            }
        }
    }
}
