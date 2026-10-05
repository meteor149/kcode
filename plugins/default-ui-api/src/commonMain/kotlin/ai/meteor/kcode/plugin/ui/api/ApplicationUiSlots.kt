package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ConversationSession
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.plugin.ui.api.PersistenceFailure
import ai.meteor.kcode.ui.state.ConversationState
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ToolUseInfo
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier

/** A typed render slot. Its owner is a plugin Fiber; UI only sees the committed snapshot. */
fun interface UiRenderer<T> {
    @Composable
    fun Render(request: T)
}

fun interface ThemeRenderer {
    @Composable
    fun Render(content: @Composable () -> Unit)
}

/** A prepared draft and submission boundary shared by feature-owned configuration controls. */
data class SettingsEditorProjection(
    val draft: StoredAppSettings,
    val submit: (StoredAppSettings) -> Unit,
)

data class ChatPageRequest(
    val modifier: Modifier,
    val compact: Boolean,
    val conversation: ConversationState?,
    val conversationExecution: ConversationExecution?,
    val service: ChatService,
    val generationRunner: ChatGenerationRunner,
    val configuration: ModelConfiguration?,
    val onConfigurationChange: (ModelConfiguration) -> Unit,
    val onMenu: () -> Unit,
    val onSettings: () -> Unit,
    val onNewConversation: () -> Unit,
    val onSendToNew: (String) -> ConversationState,
    val historyRepository: ConversationHistoryRepository,
    val goalSessionFactory: GoalSessionFactory,
    val scheduledTaskCoordinator: ScheduledTaskCoordinator,
    val settingsEditor: SettingsEditorProjection? = null,
)


data class SidebarPageRequest(
    val destination: String?,
    val navigation: List<NavigationDestination>,
    val conversationActionsAvailable: Boolean,
    val settingsAvailable: Boolean,
    val conversations: List<ConversationState>,
    val activeId: Long?,
    val onNew: () -> Unit,
    val onSelect: (Long) -> Unit,
    val onPin: (Long) -> Unit,
    val onDelete: (Long) -> Unit,
    val onSettings: () -> Unit,
    val onDestination: (String) -> Unit,
    val compact: Boolean = false,
    val width: Dp = 276.dp,
)

data class ApplicationLayoutRequest(
    val width: Dp,
    val sidebarOpen: Boolean,
    val onSidebarOpenChange: (Boolean) -> Unit,
    val sidebar: SidebarPageRequest,
    val sidebarRenderer: UiRenderer<SidebarPageRequest>?,
    val content: @Composable (Modifier, Boolean) -> Unit,
)

/** Read-only transcript surface; the consumer owns the window, the provider owns message UI. */
data class ConversationTranscriptRequest(
    val messageIds: List<Long>,
    val messageForId: (Long) -> ChatMessage?,
)

data class StandaloneConversationRequest(
    val conversation: ConversationState,
    val pendingCount: Int,
    val onAddToRecent: () -> Unit,
    val onClose: () -> Unit,
)


data class SettingsPageRequest(
    val appSettings: StoredAppSettings,
    val persistenceFailure: PersistenceFailure?,
    val onSettingsChange: (StoredAppSettings) -> Unit,
    val onDismiss: () -> Unit,
    val sections: List<SettingsSection> = emptyList(),
)

data class SettingsSectionRequest(val page: SettingsPageRequest, val onReturn: () -> Unit)

/** Registration metadata and renderer travel together, and are removed together. */
data class SettingsSection(
    val id: String,
    val order: Int,
    val icon: KcodeIconAsset,
    val title: @Composable () -> String,
    val description: @Composable (SettingsPageRequest) -> String,
    val renderer: UiRenderer<SettingsSectionRequest>,
    val isVisible: @Composable (SettingsPageRequest) -> Boolean = { true },
    val texts: Map<String, String> = emptyMap(),
    /** User navigation may be deferred by unsaved editors. Withdrawal does not invoke this gate. */
    val onLeave: (proceed: () -> Unit) -> Unit = { proceed -> proceed() },
)



data class MessageContentRequest(
    val message: ChatMessage,
    val compact: Boolean,
    val onLongPressText: (String, Int) -> Unit,
)

data class ToolUseContentRequest(val toolUse: ToolUseInfo, val compact: Boolean)

/** Lower order wins, with stable id as a deterministic tie breaker. */
data class MessagePresentation(
    val id: String,
    val order: Int,
    val supports: (ChatMessage) -> Boolean,
    val renderer: UiRenderer<MessageContentRequest>,
)

data class ToolUsePresentation(
    val id: String,
    val order: Int,
    val supports: (ToolUseInfo) -> Boolean,
    val renderer: UiRenderer<ToolUseContentRequest>,
)

data class ApplicationEffectRequest(
    val conversationSession: ConversationSession,
    val conversationExecution: ConversationExecution? = null,
    val configuration: ModelConfiguration?,
    val chatService: ChatService,
    val generationRunner: ChatGenerationRunner,
    val historyRepository: ConversationHistoryRepository,
    val goalSessionFactory: GoalSessionFactory,
    val scheduledTaskCoordinator: ScheduledTaskCoordinator,
    val language: AppLanguage,
)

data class ApplicationEffect(
    val id: String,
    val order: Int,
    val renderer: UiRenderer<ApplicationEffectRequest>,
)

val LocalApplicationUiSlots = staticCompositionLocalOf { ApplicationUiSlots() }

/** Resolve page-owned capabilities only during frame preparation. */
fun interface NavigationPagePresenter {
    suspend fun prepare(services: ApplicationServices): UiRenderer<NavigationPageRequest>?
}

/** Each route owns its metadata and renderer; the shell treats route ids as opaque strings. */
data class NavigationDestination(
    val id: String,
    val order: Int,
    val icon: KcodeIconAsset,
    val title: @Composable () -> String,
    val renderer: UiRenderer<NavigationPageRequest>,
    val handlesConversations: Boolean = false,
    val isAvailable: (ApplicationUiSlots) -> Boolean = { true },
    val presenter: NavigationPagePresenter? = null,
    /** Stable for one registration even when its prepared renderer changes between frames. */
    val renderKey: Any = renderer,
)

data class NavigationPageRequest(
    val slots: ApplicationUiSlots,
    val conversationSession: ConversationSession?,
    val modifier: Modifier,
    val compact: Boolean,
    val settingsEditor: SettingsEditorProjection,
    val committedSettings: StoredAppSettings,
    val hostOptions: ApplicationHostOptions,
    val onMenu: () -> Unit,
    val onSettings: () -> Unit,
    val onNewConversation: () -> Unit,
    val onNavigate: (String) -> Unit,
)

fun resolveNavigationDestination(selected: String?, destinations: List<NavigationDestination>): String? =
    selected?.takeIf { id -> destinations.any { it.id == id } } ?: destinations.firstOrNull()?.id

/** Missing slots stay empty; there is no implicit fallback that revives a disabled plugin. */
data class ApplicationUiSlots(
    val chat: UiRenderer<ChatPageRequest>? = null,
    val layout: UiRenderer<ApplicationLayoutRequest>? = null,
    val sidebar: UiRenderer<SidebarPageRequest>? = null,
    val standaloneConversation: UiRenderer<StandaloneConversationRequest>? = null,
    val conversationTranscript: UiRenderer<ConversationTranscriptRequest>? = null,
    val settings: UiRenderer<SettingsPageRequest>? = null,
    val theme: ThemeRenderer? = null,
    val markdown: MarkdownContent? = null,
    val localization: ai.meteor.kcode.localization.TranslationCatalog? = null,
    val settingsSections: List<SettingsSection> = emptyList(),
    val messagePresentations: List<MessagePresentation> = emptyList(),
    val toolUsePresentations: List<ToolUsePresentation> = emptyList(),
    val effects: List<ApplicationEffect> = emptyList(),
    val navigation: List<NavigationDestination> = emptyList(),
    val conversationDecorations: List<ConversationDecoration> = emptyList(),
    val conversationEffects: List<ConversationPageEffect> = emptyList(),
    val textDictionaries: List<UiTextDictionary> = emptyList(),
)
