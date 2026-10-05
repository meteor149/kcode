package ai.meteor.kcode.plugin.defaultui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.UiSlotKey
import ai.meteor.kcode.plugin.ui.api.ApplicationEffect
import ai.meteor.kcode.plugin.ui.api.ApplicationLayoutRequest
import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.ApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.ChatPageRequest
import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.ConversationMoreMenu
import ai.meteor.kcode.plugin.ui.api.ConversationPageEffect
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.MessageContentRequest
import ai.meteor.kcode.plugin.ui.api.MessagePresentation
import ai.meteor.kcode.plugin.ui.api.NavigationDestination
import ai.meteor.kcode.plugin.ui.api.NavigationPagePresenter
import ai.meteor.kcode.plugin.ui.api.NavigationPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.SidebarPageRequest
import ai.meteor.kcode.plugin.ui.api.StandaloneConversationRequest
import ai.meteor.kcode.plugin.ui.api.ThemeRenderer
import ai.meteor.kcode.plugin.ui.api.ToolUsePresentation
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable

internal class OwnedUiSlots(ctx: Context) : KcodeUiSlots(ctx) {
    private val open = MutableStateFlow(true)
    private val closeCompletion = CompletableDeferred<Unit>()
    private val registrations = mutableSetOf<Registration>()

    private class Registration {
        val active = MutableStateFlow(true)
        val operations = PluginOperationOwner("Default UI contribution preparation")
        fun revoke() { active.value = false }
        fun requireActive() { check(active.value) { "UI contribution was withdrawn" } }
    }

    private fun requireOpen() { check(open.value) { "Default UI registry was closed" } }

    private fun <T> guardedRenderer(renderer: UiRenderer<T>, registration: Registration): UiRenderer<T> =
        UiRenderer { request ->
            if (open.collectAsState().value && registration.active.collectAsState().value) {
                renderer.Render(guardRequest(request, registration))
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> guardedValue(value: T, registration: Registration): T = when (value) {
        is UiRenderer<*> -> guardedRenderer(value as UiRenderer<Any?>, registration)
        is ThemeRenderer -> ThemeRenderer { content ->
            if (open.collectAsState().value && registration.active.collectAsState().value) value.Render(content)
        }
        else -> value
    } as T

    private val conversationDecorations = mutableMapOf<String, ConversationDecoration>()
    private val conversationEffects = mutableMapOf<String, ConversationPageEffect>()
    private val mutex = Mutex()
    private val effects = mutableMapOf<String, ApplicationEffect>()
    private val navigation = mutableMapOf<String, NavigationDestination>()
    private val messages = mutableMapOf<String, MessagePresentation>()
    private val toolUses = mutableMapOf<String, ToolUsePresentation>()
    private val sections = mutableMapOf<String, SettingsSection>()
    private val renderers = mutableMapOf<String, Any>()
    private val textDictionaries = mutableMapOf<String, UiTextDictionary>()

    private suspend fun <K : Any, V : Any> registerContribution(
        entries: MutableMap<K, V>,
        key: K,
        value: V,
        label: String,
        registration: Registration = Registration(),
    ): Disposable {
        // Each disposer owns one registration, even if a replacement reuses the same value.
        var disposed = false
        mutex.withLock {
            requireOpen()
            require(key !in entries) { "$label is already registered" }
            entries[key] = value
            registrations.add(registration)
        }
        return Disposable {
            registration.operations.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!disposed) {
                        registration.revoke()
                        entries.remove(key)
                        disposed = true
                    }
                }
                try { registration.operations.close() }
                finally { mutex.withLock { registrations.remove(registration) } }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(slot: UiSlotKey<T>): T? = renderers[slot.id] as T?

    /** Resolve a default or plugin-defined slot by its contract ID during frame preparation. */
    override suspend fun <T : Any> resolve(slot: UiSlotKey<T>): T? = mutex.withLock {
        requireOpen()
        value(slot)
    }

    override suspend fun <T : Any> register(slot: UiSlotKey<T>, renderer: T): Disposable {
        val registration = Registration()
        return registerContribution(renderers, slot.id, guardedValue(renderer, registration), "UI slot '${slot.id}'", registration)
    }

    override suspend fun registerTexts(dictionary: UiTextDictionary): Disposable {
        val owner = Registration()
        val guarded = UiTextDictionary(dictionary.id, dictionary.values, owner.active.asStateFlow())
        mutex.withLock {
            requireOpen()
            require(dictionary.id !in textDictionaries) { "UI texts '${dictionary.id}' are already registered" }
            guarded.values.forEach { (key, value) ->
                require(textDictionaries.values.all { it.values[key]?.let { existing -> existing == value } != false }) {
                    "Conflicting default UI text '$key'"
                }
            }
            textDictionaries[dictionary.id] = guarded
            registrations.add(owner)
        }
        var disposed = false
        return Disposable {
            owner.revoke()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!disposed) {
                        textDictionaries.remove(dictionary.id)
                        disposed = true
                    }
                }
                try { owner.operations.close() }
                finally { mutex.withLock { registrations.remove(owner) } }
            }
        }
    }

    override suspend fun registerSettings(section: SettingsSection): Disposable {
        require(section.id.isNotBlank()) { "Settings section id must not be blank" }
        val registrationOwner = Registration()
        val active = registrationOwner.active
        val guarded = section.copy(
            title = { if (active.collectAsState().value) section.title() else "" },
            description = { if (active.collectAsState().value) section.description(it) else "" },
            isVisible = { active.collectAsState().value && section.isVisible(it) },
            renderer = guardedRenderer(section.renderer, registrationOwner),
        )
        val texts = if (section.texts.isEmpty()) null else registerTexts(UiTextDictionary("settings.${section.id}", section.texts))
        val registration = try {
            registerContribution(sections, section.id, guarded, "Settings section '${section.id}'", registrationOwner)
        } catch (error: Throwable) {
            texts?.dispose()
            throw error
        }
        return Disposable {
            active.value = false
            try { registration.dispose() } finally { texts?.dispose() }
        }
    }

    override suspend fun registerMessage(presentation: MessagePresentation): Disposable {
        require(presentation.id.isNotBlank()) { "Message presentation id must not be blank" }
        val owner = Registration()
        val guarded = presentation.copy(
            supports = { owner.active.value && open.value && presentation.supports(it) },
            renderer = guardedRenderer(presentation.renderer, owner),
        )
        return registerContribution(messages, presentation.id, guarded, "Message presentation '${presentation.id}'", owner)
    }

    override suspend fun registerToolUse(presentation: ToolUsePresentation): Disposable {
        require(presentation.id.isNotBlank()) { "Tool presentation id must not be blank" }
        val owner = Registration()
        val guarded = presentation.copy(
            supports = { owner.active.value && open.value && presentation.supports(it) },
            renderer = guardedRenderer(presentation.renderer, owner),
        )
        return registerContribution(toolUses, presentation.id, guarded, "Tool presentation '${presentation.id}'", owner)
    }

    override suspend fun registerEffect(effect: ApplicationEffect): Disposable {
        require(effect.id.isNotBlank()) { "Application effect id must not be blank" }
        val owner = Registration()
        return registerContribution(effects, effect.id, effect.copy(renderer = guardedRenderer(effect.renderer, owner)), "Application effect '${effect.id}'", owner)
    }

    override suspend fun registerNavigation(destination: NavigationDestination): Disposable {
        require(destination.id.isNotBlank()) { "Navigation destination id must not be blank" }
        val owner = Registration()
        val guarded = destination.copy(
            renderKey = owner,
            title = { if (open.collectAsState().value && owner.active.collectAsState().value) destination.title() else "" },
            isAvailable = { open.value && owner.active.value && destination.isAvailable(it) },
            renderer = guardedRenderer(destination.renderer, owner),
            presenter = destination.presenter?.let { presenter -> NavigationPagePresenter { services ->
                owner.operations.run {
                    owner.requireActive()
                    requireOpen()
                    presenter.prepare(services)?.let { guardedRenderer(it, owner) }
                }
            } },
        )
        return registerContribution(navigation, destination.id, guarded, "Navigation destination '${destination.id}'", owner)
    }

    override suspend fun registerConversationDecoration(decoration: ConversationDecoration): Disposable {
        require(decoration.id.isNotBlank())
        val owner = Registration()
        val guarded = decoration.copy(presenter = ConversationDecorationPresenter { context ->
            if (open.collectAsState().value && owner.active.collectAsState().value) {
                decoration.presenter.Present(guardRequest(context, owner)).map { it.copy(renderer = guardedRenderer(it.renderer, owner)) }
            } else emptyList()
        })
        return registerContribution(conversationDecorations, decoration.id, guarded, "Conversation decoration '${decoration.id}'", owner)
    }

    override suspend fun registerConversationEffect(effect: ConversationPageEffect): Disposable {
        require(effect.id.isNotBlank())
        val owner = Registration()
        return registerContribution(conversationEffects, effect.id, effect.copy(renderer = guardedRenderer(effect.renderer, owner)), "Conversation effect '${effect.id}'", owner)
    }

    override suspend fun snapshot(): ApplicationUiSlots = mutex.withLock {
        requireOpen()
        ApplicationUiSlots(
            layout = value(ApplicationSlots.Layout),
            sidebar = value(ApplicationSlots.Sidebar),
            chat = value(ApplicationSlots.Chat),
            conversationTranscript = value(ApplicationSlots.ConversationTranscript),
            standaloneConversation = value(ApplicationSlots.StandaloneConversation),
            settings = value(ApplicationSlots.Settings),
            localization = value(ApplicationSlots.Localization),
            markdown = value(ApplicationSlots.Markdown),
            theme = value(ApplicationSlots.Theme),
            textDictionaries = textDictionaries.values.sortedBy { it.id },
            effects = effects.values.sortedWith(compareBy({ it.order }, { it.id })),
            conversationDecorations = conversationDecorations.values.sortedWith(compareBy({ it.order }, { it.id })),
            conversationEffects = conversationEffects.values.sortedWith(compareBy({ it.order }, { it.id })),
            navigation = navigation.values.sortedWith(compareBy({ it.order }, { it.id })),
            settingsSections = sections.values.sortedWith(compareBy({ it.order }, { it.id })),
            messagePresentations = messages.values.sortedWith(compareBy({ it.order }, { it.id })),
            toolUsePresentations = toolUses.values.sortedWith(compareBy({ it.order }, { it.id })),
        )
    }

    override suspend fun close() {
        PluginOperationOwner.requireOutsideCall()
        withContext(NonCancellable) {
            val retired = mutex.withLock {
                if (!open.value) return@withLock null
                open.value = false
                val captured = registrations.toList()
                captured.forEach { it.revoke() }
                registrations.clear()
                renderers.clear()
                textDictionaries.clear()
                sections.clear()
                messages.clear()
                toolUses.clear()
                effects.clear()
                navigation.clear()
                conversationDecorations.clear()
                conversationEffects.clear()
                captured
            }
            if (retired == null) {
                closeCompletion.await()
                return@withContext
            }
            try {
                var failure: Throwable? = null
                retired.forEach { registration ->
                    try { registration.operations.close() }
                    catch (error: Throwable) {
                        if (failure == null) failure = error else failure.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
                closeCompletion.complete(Unit)
            } catch (error: Throwable) {
                closeCompletion.completeExceptionally(error)
                throw error
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> guardRequest(request: T, owner: Registration): T {
        fun action(block: () -> Unit): () -> Unit = { owner.requireActive(); requireOpen(); block() }
        return when (request) {
            is ChatPageRequest -> request.copy(
                onMenu = action(request.onMenu),
                onSettings = action(request.onSettings),
                onNewConversation = action(request.onNewConversation),
                onConfigurationChange = { owner.requireActive(); requireOpen(); request.onConfigurationChange(it) },
                onSendToNew = { owner.requireActive(); requireOpen(); request.onSendToNew(it) },
                settingsEditor = request.settingsEditor?.let { editor -> editor.copy(
                    submit = { owner.requireActive(); requireOpen(); editor.submit(it) },
                ) },
            )
            is NavigationPageRequest -> request.copy(
                onNavigate = { owner.requireActive(); requireOpen(); request.onNavigate(it) },
                onMenu = action(request.onMenu),
                onSettings = action(request.onSettings),
                onNewConversation = action(request.onNewConversation),
                settingsEditor = request.settingsEditor.copy(
                    submit = { owner.requireActive(); requireOpen(); request.settingsEditor.submit(it) },
                ),
            )
            is SettingsPageRequest -> request.copy(
                onDismiss = action(request.onDismiss),
                onSettingsChange = { owner.requireActive(); requireOpen(); request.onSettingsChange(it) },
            )
            is SettingsSectionRequest -> request.copy(
                page = guardRequest(request.page, owner),
                onReturn = action(request.onReturn),
            )
            is SidebarPageRequest -> request.copy(
                onNew = action(request.onNew),
                onSettings = action(request.onSettings),
                onSelect = { owner.requireActive(); requireOpen(); request.onSelect(it) },
                onPin = { owner.requireActive(); requireOpen(); request.onPin(it) },
                onDelete = { owner.requireActive(); requireOpen(); request.onDelete(it) },
                onDestination = { owner.requireActive(); requireOpen(); request.onDestination(it) },
            )
            is ApplicationLayoutRequest -> request.copy(
                onSidebarOpenChange = { owner.requireActive(); requireOpen(); request.onSidebarOpenChange(it) },
                sidebar = guardRequest(request.sidebar, owner),
            )
            is ConversationPageContext -> request.copy(
                moreMenu = request.moreMenu?.let { menu -> object : ConversationMoreMenu {
                    override fun showPage(renderer: UiRenderer<androidx.compose.ui.Modifier>) {
                        owner.requireActive()
                        requireOpen()
                        menu.showPage(guardedRenderer(renderer, owner))
                    }
                    override fun dismiss() {
                        owner.requireActive()
                        requireOpen()
                        menu.dismiss()
                    }
                } },
                clearSelection = action(request.clearSelection),
                beforeAction = action(request.beforeAction),
                followBottom = { owner.requireActive(); requireOpen(); request.followBottom(it) },
                settingsEditor = request.settingsEditor?.let { editor -> editor.copy(
                    submit = { owner.requireActive(); requireOpen(); editor.submit(it) },
                ) },
            )
            is MessageContentRequest -> request.copy(
                onLongPressText = { text, index -> owner.requireActive(); requireOpen(); request.onLongPressText(text, index) },
            )
            is StandaloneConversationRequest -> request.copy(
                onAddToRecent = action(request.onAddToRecent),
                onClose = action(request.onClose),
            )
            else -> request
        } as T
    }
}
