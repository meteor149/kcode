package ai.meteor.kcode.plugin.ui.api

import ai.meteor.kcode.plugin.api.UiSlotKey
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.Service
import org.cordis.ServiceKey

/** Stable typed keys; each slot has exactly one owner, duplicate registrations fail at apply. */
object ApplicationSlots {
    val Layout = UiSlotKey<UiRenderer<ApplicationLayoutRequest>>("application.layout")
    val Sidebar = UiSlotKey<UiRenderer<SidebarPageRequest>>("page.sidebar")
    val Chat = UiSlotKey<UiRenderer<ChatPageRequest>>("page.chat")
    val ConversationTranscript = UiSlotKey<UiRenderer<ConversationTranscriptRequest>>("conversation.transcript")
    val StandaloneConversation = UiSlotKey<UiRenderer<StandaloneConversationRequest>>("conversation.standalone")
    val Settings = UiSlotKey<UiRenderer<SettingsPageRequest>>("page.settings")
    val Localization = UiSlotKey<ai.meteor.kcode.localization.TranslationCatalog>("content.localization")
    val Markdown = UiSlotKey<MarkdownContent>("content.markdown")
    val Theme = UiSlotKey<ThemeRenderer>("theme")
}

/** Shared default UI vocabulary; implementations and lifecycle belong to its provider. */
abstract class KcodeUiSlots(ctx: Context) : Service<Unit>(ctx, Key) {
    abstract suspend fun <T : Any> resolve(slot: UiSlotKey<T>): T?
    abstract suspend fun <T : Any> register(slot: UiSlotKey<T>, renderer: T): Disposable
    abstract suspend fun registerTexts(dictionary: UiTextDictionary): Disposable
    abstract suspend fun registerSettings(section: SettingsSection): Disposable
    abstract suspend fun registerMessage(presentation: MessagePresentation): Disposable
    abstract suspend fun registerToolUse(presentation: ToolUsePresentation): Disposable
    abstract suspend fun registerEffect(effect: ApplicationEffect): Disposable
    abstract suspend fun registerNavigation(destination: NavigationDestination): Disposable
    abstract suspend fun registerConversationDecoration(decoration: ConversationDecoration): Disposable
    abstract suspend fun registerConversationEffect(effect: ConversationPageEffect): Disposable
    abstract suspend fun snapshot(): ApplicationUiSlots
    abstract suspend fun close()

    companion object { val Key = ServiceKey<KcodeUiSlots>("uiSlots") }
}
