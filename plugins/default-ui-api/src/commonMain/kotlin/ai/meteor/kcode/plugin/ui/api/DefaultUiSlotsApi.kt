package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.api.UiSlotKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

/** Stable typed keys; each slot has exactly one owner, duplicate registrations fail at apply. */
object ApplicationSlots {
    val Layout = UiSlotKey<UiRenderer<ApplicationLayoutRequest>>("application.layout")
    val Sidebar = UiSlotKey<UiRenderer<SidebarPageRequest>>("page.sidebar")
    val Chat = UiSlotKey<UiRenderer<ChatPageRequest>>("page.chat")
    val WebContainers = UiSlotKey<UiRenderer<WebContainersOverlayRequest>>("web-containers.overlay")
    val ConversationTranscript = UiSlotKey<UiRenderer<ConversationTranscriptRequest>>("conversation.transcript")
    val StandaloneConversation = UiSlotKey<UiRenderer<StandaloneConversationRequest>>("conversation.standalone")
    val Artifacts = UiSlotKey<UiRenderer<ArtifactsPageRequest>>("page.artifacts")
    val Settings = UiSlotKey<UiRenderer<SettingsPageRequest>>("page.settings")
    val Localization = UiSlotKey<ai.meteor.kcode.localization.TranslationCatalog>("content.localization")
    val Markdown = UiSlotKey<MarkdownContent>("content.markdown")
    val Theme = UiSlotKey<ThemeRenderer>("theme")
}

class KcodeUiSlots(ctx: Context) : Service<Unit>(ctx, Key) {
    private val conversationDecorations = mutableMapOf<String, ConversationDecoration>()
    private val conversationEffects = mutableMapOf<String, ConversationPageEffect>()
    private val mutex = Mutex()
    private val effects = mutableMapOf<String, ApplicationEffect>()
    private val navigation = mutableMapOf<String, NavigationDestination>()
    private val messages = mutableMapOf<String, MessagePresentation>()
    private val toolUses = mutableMapOf<String, ToolUsePresentation>()
    private val sections = mutableMapOf<String, SettingsSection>()
    private val renderers = mutableMapOf<String, Any>()

    private suspend fun <K : Any, V : Any> registerContribution(
        entries: MutableMap<K, V>,
        key: K,
        value: V,
        label: String,
    ): Disposable {
        // Each disposer owns one registration, even if a replacement reuses the same value.
        var disposed = false
        mutex.withLock {
            require(key !in entries) { "$label is already registered" }
            entries[key] = value
        }
        return Disposable {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!disposed) {
                        entries.remove(key)
                        disposed = true
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(slot: UiSlotKey<T>): T? = renderers[slot.id] as T?

    /** Resolve a default or plugin-defined slot by its contract ID during frame preparation. */
    suspend fun <T : Any> resolve(slot: UiSlotKey<T>): T? = mutex.withLock {
        value(slot)
    }

    suspend fun <T : Any> register(slot: UiSlotKey<T>, renderer: T): Disposable {
        return registerContribution(renderers, slot.id, renderer, "UI slot '${slot.id}'")
    }

    suspend fun registerSettings(section: SettingsSection): Disposable {
        require(section.id.isNotBlank()) { "Settings section id must not be blank" }
        return registerContribution(sections, section.id, section, "Settings section '${section.id}'")
    }

    suspend fun registerMessage(presentation: MessagePresentation): Disposable {
        require(presentation.id.isNotBlank()) { "Message presentation id must not be blank" }
        return registerContribution(messages, presentation.id, presentation, "Message presentation '${presentation.id}'")
    }

    suspend fun registerToolUse(presentation: ToolUsePresentation): Disposable {
        require(presentation.id.isNotBlank()) { "Tool presentation id must not be blank" }
        return registerContribution(toolUses, presentation.id, presentation, "Tool presentation '${presentation.id}'")
    }

    suspend fun registerEffect(effect: ApplicationEffect): Disposable {
        require(effect.id.isNotBlank()) { "Application effect id must not be blank" }
        return registerContribution(effects, effect.id, effect, "Application effect '${effect.id}'")
    }

    suspend fun registerNavigation(destination: NavigationDestination): Disposable {
        require(destination.id.isNotBlank()) { "Navigation destination id must not be blank" }
        return registerContribution(navigation, destination.id, destination, "Navigation destination '${destination.id}'")
    }

    suspend fun registerConversationDecoration(decoration: ConversationDecoration): Disposable {
        require(decoration.id.isNotBlank())
        return registerContribution(conversationDecorations, decoration.id, decoration, "Conversation decoration '${decoration.id}'")
    }

    suspend fun registerConversationEffect(effect: ConversationPageEffect): Disposable {
        require(effect.id.isNotBlank())
        return registerContribution(conversationEffects, effect.id, effect, "Conversation effect '${effect.id}'")
    }

    suspend fun snapshot(): ApplicationUiSlots = mutex.withLock {
        ApplicationUiSlots(
            layout = value(ApplicationSlots.Layout),
            sidebar = value(ApplicationSlots.Sidebar),
            chat = value(ApplicationSlots.Chat),
            webContainers = value(ApplicationSlots.WebContainers),
            conversationTranscript = value(ApplicationSlots.ConversationTranscript),
            standaloneConversation = value(ApplicationSlots.StandaloneConversation),
            artifacts = value(ApplicationSlots.Artifacts),
            settings = value(ApplicationSlots.Settings),
            localization = value(ApplicationSlots.Localization),
            markdown = value(ApplicationSlots.Markdown),
            theme = value(ApplicationSlots.Theme),
            effects = effects.values.sortedWith(compareBy({ it.order }, { it.id })),
            conversationDecorations = conversationDecorations.values.sortedWith(compareBy({ it.order }, { it.id })),
            conversationEffects = conversationEffects.values.sortedWith(compareBy({ it.order }, { it.id })),
            navigation = navigation.values.sortedWith(compareBy({ it.order }, { it.id })),
            settingsSections = sections.values.sortedWith(compareBy({ it.order }, { it.id })),
            messagePresentations = messages.values.sortedWith(compareBy({ it.order }, { it.id })),
            toolUsePresentations = toolUses.values.sortedWith(compareBy({ it.order }, { it.id })),
        )
    }

    companion object { val Key = ServiceKey<KcodeUiSlots>("uiSlots") }
}
