package ai.meteor.kcode.plugin.overlay

import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.plugin.api.ConversationOverlayHostState
import ai.meteor.kcode.plugin.api.KcodeConversationOverlays
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object ConversationOverlaysServicePlugin : Plugin<ConversationOverlayHostState> {
    override val name = "kcode-conversation-overlays"
    override suspend fun apply(ctx: Context, config: ConversationOverlayHostState, effect: EffectScope) {
        val registry = KcodeConversationOverlays(ctx, config)
        effect.collect { registry.close() }
    }
}

/** Serializable entry borrows the kernel's committed UI/foreground projection. */
object NativeConversationOverlaysServicePlugin : Plugin<Unit> {
    override val name = "kcode-native-conversation-overlays"
    override val config = ConfigValidator<Unit> { it }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val state = requireNotNull(ConversationOverlayHostState.current(ctx)) {
            "Conversation overlay registry requires runtime host state"
        }
        ConversationOverlaysServicePlugin.apply(ctx, state, effect)
    }
}

object ConversationOverlayProviderPlugin : Plugin<ConversationOverlayFactory> {
    override val name = "kcode-conversation-overlay-provider"
    override val inject = dependencies(KcodeConversationOverlays.Key)
    override suspend fun apply(ctx: Context, config: ConversationOverlayFactory, effect: EffectScope) {
        val registry = ctx.require(KcodeConversationOverlays.Key)
        val owned = withContext(NonCancellable) {
            config.create()?.let { delegate ->
                OwnedConversationOverlayController(delegate).also { controller ->
                    effect.collect {
                        controller.requireCanClose()
                        registry.unregister(controller)
                        controller.close()
                    }
                }
            }
        } ?: return
        if (!effect.isActive) return
        registry.register(owned)
    }
}

internal class OwnedConversationOverlayController(
    private val delegate: AgentConversationOverlayController,
) : AgentConversationOverlayController {
    private val owner = PluginOperationOwner("Conversation overlay provider")
    private val mutex = Mutex()
    private val turns = mutableSetOf<OwnedTurn>()
    private var closing = false
    private val completion = CompletableDeferred<Unit>()

    override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn = owner.acquire(
        acquire = {
            val turn = OwnedTurn(delegate.startTurn(initialMessages))
            mutex.withLock { turns += turn }
            turn
        },
        discard = { it.finishOwned() },
    )

    override suspend fun setHostForeground(isForeground: Boolean) = owner.run {
        delegate.setHostForeground(isForeground)
    }

    suspend fun requireCanClose() = owner.requireCanClose()

    override suspend fun close() {
        owner.requireCanClose()
        withContext(NonCancellable) {
            val first = mutex.withLock { if (closing) false else { closing = true; true } }
            if (!first) {
                completion.await()
                return@withContext
            }
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) { failures += error }
            }
            attempt { owner.close() }
            mutex.withLock { turns.toList() }.forEach { turn -> attempt { turn.finishOwned() } }
            attempt { delegate.close() }
            val error = failures.takeIf { it.isNotEmpty() }?.let { PluginCleanupException("Conversation overlay provider", it) }
            if (error == null) completion.complete(Unit) else completion.completeExceptionally(error)
            completion.await()
        }
    }

    private inner class OwnedTurn(private val delegate: AgentConversationOverlayTurn) : AgentConversationOverlayTurn {
        private val turnMutex = Mutex()
        private var finished = false
        override suspend fun update(messages: List<ChatMessage>) = owner.run {
            turnMutex.withLock {
                check(!finished) { "Conversation overlay turn is finished" }
                delegate.update(messages)
            }
        }
        override suspend fun finish() = owner.run { finishOwned() }
        suspend fun finishOwned() = withContext(NonCancellable) {
            turnMutex.withLock {
                if (!finished) {
                    finished = true
                    try { delegate.finish() } finally { mutex.withLock { turns.remove(this@OwnedTurn) } }
                }
            }
            Unit
        }
    }
}
