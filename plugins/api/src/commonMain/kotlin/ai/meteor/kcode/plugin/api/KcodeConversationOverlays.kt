package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cordis.Context
import org.cordis.InterceptKey
import org.cordis.Service
import org.cordis.ServiceKey

/** Bounded allocation; the effect owns even a controller returned after cancellation. */
fun interface ConversationOverlayFactory {
    suspend fun create(): AgentConversationOverlayController?
}

/** Host lifecycle state survives registry and provider generations. */
class ConversationOverlayHostState(
    val uiSlots: StateFlow<UiContributionsSnapshot> = MutableStateFlow(UiContributionsSnapshot()).asStateFlow(),
) {
    /** Borrowed runtime state survives registry/provider generations; it owns no native resources. */
    fun bind(context: Context): Context = context.intercept(HostStateKey, this)

    companion object {
        private val HostStateKey = InterceptKey<ConversationOverlayHostState>("kcode.conversation-overlay.host-state")

        fun current(context: Context): ConversationOverlayHostState? =
            context.interceptValues(HostStateKey).lastOrNull()
    }

    private val foreground = MutableStateFlow(true)
    val isForeground: Boolean get() = foreground.value
    fun setForeground(value: Boolean) { foreground.value = value }
}

/** Optional platform presentation; absence never constructs a default implementation. */
class KcodeConversationOverlays(
    ctx: Context,
    private val hostState: ConversationOverlayHostState = ConversationOverlayHostState(),
) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeConversationOverlays>("conversationOverlays") }
    /** Current composition projection, shared across provider generations. */
    val uiSlots: StateFlow<UiContributionsSnapshot> get() = hostState.uiSlots

    private val mutex = Mutex()
    private var controller: AgentConversationOverlayController? = null
    private var closed = false

    suspend fun register(value: AgentConversationOverlayController) = mutex.withLock {
        check(!closed) { "Conversation overlay registry is closed" }
        check(controller == null) { "A conversation overlay provider is already registered" }
        value.setHostForeground(hostState.isForeground)
        controller = value
    }

    suspend fun unregister(value: AgentConversationOverlayController) = mutex.withLock {
        if (controller === value) controller = null
    }

    suspend fun current(): AgentConversationOverlayController? = mutex.withLock {
        check(!closed) { "Conversation overlay registry is closed" }
        controller
    }

    suspend fun startTurn(messages: List<ChatMessage>): AgentConversationOverlayTurn? =
        current()?.startTurn(messages)

    suspend fun setHostForeground(value: Boolean) = mutex.withLock {
        check(!closed) { "Conversation overlay registry is closed" }
        hostState.setForeground(value)
        controller?.setHostForeground(value)
        Unit
    }

    suspend fun close() = mutex.withLock {
        closed = true
        controller = null
    }
}
