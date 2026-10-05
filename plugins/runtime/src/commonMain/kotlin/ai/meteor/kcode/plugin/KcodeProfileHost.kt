package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.AgentRuntimeOwner
import ai.meteor.kcode.ApplicationContent
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.KcodeAgentRuntime
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.SettingsUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Preparation verifies locked metadata/code; create allocates a fresh, unpublished runtime. */
class PreparedProfileRuntime(
    val activation: ProfileActivation,
    val create: suspend () -> KcodeAgentRuntime,
)

fun interface ProfileRuntimeFactory {
    suspend fun prepare(id: String): PreparedProfileRuntime
}

enum class ProfileHostPhase { Ready, Preparing, Switching, RecoveryRequired, Closed }

data class ProfileHostState(
    val profileId: String,
    val phase: ProfileHostPhase = ProfileHostPhase.Ready,
    val failure: Throwable? = null,
)

/** Stable native facades own task admission across replacement of complete product runtimes. */
class KcodeProfileHost(
    initial: KcodeAgentRuntime,
    initialProfileId: String,
    private val factory: ProfileRuntimeFactory,
) : AgentRuntimeOwner, ApplicationContent {
    private class HostCall(val host: KcodeProfileHost) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HostCall>
    }
    private class Transition(val host: KcodeProfileHost) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Transition>
    }
    private val command = Mutex()
    private val admission = Mutex()
    private val calls = mutableMapOf<Job, Int>()
    private val overlayTurns = mutableSetOf<OverlayTurn>()
    private val view = MutableStateFlow<KcodeAgentRuntime?>(initial)
    private var current: KcodeAgentRuntime? = initial
    private val mutableState = MutableStateFlow(ProfileHostState(initialProfileId))
    val state: StateFlow<ProfileHostState> = mutableState.asStateFlow()
    private var foreground = true
    private var overlayClosed = false
    private val closeCompletion = CompletableDeferred<Unit>()

    private suspend fun outsideCall() {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        val context = currentCoroutineContext()
        check(context[HostCall]?.host !== this && context[Transition]?.host !== this) {
            "A Profile callback cannot switch or close its own host"
        }
    }

    private suspend fun <T> call(block: suspend (KcodeAgentRuntime) -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val runtime = admission.withLock {
            check(mutableState.value.phase == ProfileHostPhase.Ready) { "Profile host does not admit work: ${mutableState.value.phase}" }
            checkNotNull(current).also { calls[job] = (calls[job] ?: 0) + 1 }
        }
        try { return withContext(HostCall(this)) { block(runtime) } }
        finally {
            withContext(NonCancellable) {
                admission.withLock {
                    val remaining = calls.getValue(job) - 1
                    if (remaining == 0) calls.remove(job) else calls[job] = remaining
                }
            }
        }
    }

    val chatService: ChatService = object : ChatService {
        override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String =
            call { it.chatService.reply(configuration, history, prompt) }

        override suspend fun replyStreaming(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String,
            goalSession: GoalSession?, scheduledTaskSession: ScheduledTaskSession?, scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
            onToolUse: suspend (ToolUseEvent) -> Unit, onSubAgent: suspend (SubAgentEvent) -> Unit, onDelta: suspend (String) -> Unit): String = call {
            it.chatService.replyStreaming(configuration, history, prompt, goalSession, scheduledTaskSession,
                scheduledTaskCompletionSession, onToolUse, onSubAgent, onDelta)
        }
    }

    val pluginManager: AgentPluginManager = object : AgentPluginManager {
        override suspend fun importPackages(packages: List<PluginPackageImport>) = call { checkNotNull(it.pluginManager).importPackages(packages) }
        override suspend fun applyChanges(changes: PluginCompositionChange) = call { checkNotNull(it.pluginManager).applyChanges(changes) }
        override suspend fun install(spec: DynamicPluginSpec) = call { checkNotNull(it.pluginManager).install(spec) }
        override suspend fun replace(spec: DynamicPluginSpec) = call { checkNotNull(it.pluginManager).replace(spec) }
        override suspend fun uninstall(id: String) = call { checkNotNull(it.pluginManager).uninstall(id) }
        override suspend fun installed(): List<DynamicPluginSpec> = call { checkNotNull(it.pluginManager).installed() }
        override suspend fun setEnabled(id: String, enabled: Boolean) = call { checkNotNull(it.pluginManager).setEnabled(id, enabled) }
    }

    private inner class OverlayTurn(val delegate: AgentConversationOverlayTurn) : AgentConversationOverlayTurn {
        var finished = false
        val completion = CompletableDeferred<Unit>()
        override suspend fun update(messages: List<ChatMessage>) = call {
            admission.withLock { check(!finished && this@OverlayTurn in overlayTurns) { "Overlay turn was withdrawn" } }
            delegate.update(messages)
        }
        override suspend fun finish() {
            // Finish also works after withdrawal, but cleanup is performed only once.
            val release = admission.withLock {
                if (finished) false else { finished = true; true }
            }
            withContext(NonCancellable + HostCall(this@KcodeProfileHost)) {
                if (!release) completion.await() else {
                    try { delegate.finish(); completion.complete(Unit) }
                    catch (error: Throwable) { completion.completeExceptionally(error); throw error }
                    finally { admission.withLock { overlayTurns.remove(this@OverlayTurn) } }
                }
            }
        }
    }

    val conversationOverlayController: AgentConversationOverlayController = object : AgentConversationOverlayController {
        override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn = call { runtime ->
            check(!overlayClosed) { "Overlay controller is closed" }
            val turn = OverlayTurn(checkNotNull(runtime.conversationOverlayController) { "Conversation overlay is unavailable" }.startTurn(initialMessages))
            withContext(NonCancellable) { admission.withLock { overlayTurns += turn } }
            turn
        }
        override suspend fun setHostForeground(isForeground: Boolean) {
            outsideCall()
            command.withLock {
                foreground = isForeground
                if (mutableState.value.phase == ProfileHostPhase.Ready) {
                    call { it.conversationOverlayController?.setHostForeground(isForeground) }
                }
            }
        }
        override suspend fun close() = call {
            overlayClosed = true
            finishOverlays()
            it.conversationOverlayController?.close()
            Unit
        }
    }

    val runtime = KcodeAgentRuntime(
        chatService = chatService,
        conversationOverlayController = if (initial.conversationOverlayController == null) null else conversationOverlayController,
        pluginManager = pluginManager,
        owner = this,
        applicationContent = this,
    )

    override suspend fun updateSettings(update: SettingsUpdate): AppliedSettingsUpdate = call {
        checkNotNull(it.applicationContent).updateSettings(update)
    }
    override suspend fun modelCatalog(): ModelCatalogSnapshot = call {
        it.applicationContent?.modelCatalog() ?: ModelCatalogSnapshot()
    }
    /** Native diagnostics are read through admission; callers do not retain retired runtimes. */
    suspend fun diagnostics(): KcodePluginDiagnostics = call {
        checkNotNull(it.owner as? KcodePluginRuntime) { "Native plugin diagnostics are unavailable" }.diagnostics()
    }
    @Composable
    override fun Render(options: ApplicationHostOptions) {
        val active by view.collectAsState()
        val content = active?.applicationContent ?: return
        key(active) { content.Render(options) }
    }

    private suspend fun finishOverlays() {
        val turns = admission.withLock { overlayTurns.toList() }
        var failure: Throwable? = null
        turns.forEach { turn ->
            try { turn.finish() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    /** Default rejects active work; explicit cancellation cancels and joins it before disposal. */
    suspend fun switchTo(id: String, cancelActive: Boolean = false) {
        outsideCall()
        command.withLock {
            val previousState = admission.withLock {
                val previous = mutableState.value
                check(previous.phase == ProfileHostPhase.Ready) { "Profile host cannot switch: ${previous.phase}" }
                check(cancelActive || calls.isEmpty() && overlayTurns.isEmpty()) { "Finish or cancel active work before switching Profiles" }
                mutableState.value = previous.copy(phase = ProfileHostPhase.Preparing, failure = null)
                previous
            }
            var target: PreparedProfileRuntime? = null
            var recovery: PreparedProfileRuntime? = null
            var candidate: KcodeAgentRuntime? = null
            var withdrawn = false
            var previousReleased = false
            var published = false
            var recoveryResumed = false
            try {
                withContext(Transition(this)) {
                    if (cancelActive) withContext(NonCancellable) {
                        val jobs = admission.withLock { calls.keys.toList() }
                        jobs.forEach { it.cancel() }
                        jobs.forEach { it.join() }
                        finishOverlays()
                    }
                    target = factory.prepare(id)
                    check(target!!.activation.resolved.definition.id == id) { "Prepared Profile identity mismatch" }
                    target!!.activation.session.requirePreparedSwitch()
                    recovery = factory.prepare(previousState.profileId)
                    check(recovery!!.activation.resolved.definition.id == previousState.profileId) { "Recovery Profile identity mismatch" }
                    recovery!!.activation.session.requirePreparedSwitch()
                    currentCoroutineContext().ensureActive()
                    // Closure and commit finalization must complete even if the requesting UI is cancelled.
                    withContext(NonCancellable) {
                        finishOverlays()
                        val old = admission.withLock {
                            mutableState.value = previousState.copy(phase = ProfileHostPhase.Switching)
                            view.value = null
                            withdrawn = true
                            checkNotNull(current).also { current = null }
                        }
                        old.close()
                        previousReleased = true
                    }
                    currentCoroutineContext().ensureActive()
                    candidate = target!!.create()
                    candidate!!.conversationOverlayController?.setHostForeground(foreground)
                    withContext(NonCancellable) {
                        target!!.activation.session.publishPreparedSwitch()
                        published = true
                        admission.withLock {
                            current = candidate
                            view.value = candidate
                            mutableState.value = ProfileHostState(id)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (published) throw error
                withContext(NonCancellable + Transition(this)) {
                    suspend fun release(action: suspend () -> Unit) {
                        try { action() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    }
                    release { candidate?.close() }
                    release { target?.activation?.session?.discardPreparedSwitch() }
                    if (!withdrawn) admission.withLock { mutableState.value = previousState.copy(failure = error) }
                    else if (!previousReleased) admission.withLock {
                        mutableState.value = previousState.copy(phase = ProfileHostPhase.RecoveryRequired, failure = error)
                    } else {
                        var restored: KcodeAgentRuntime? = null
                        try {
                            restored = checkNotNull(recovery).create()
                            restored.conversationOverlayController?.setHostForeground(foreground)
                            recovery!!.activation.session.resumePreparedRestoration()
                            recoveryResumed = true
                            admission.withLock {
                                current = restored
                                view.value = restored
                                mutableState.value = previousState.copy(failure = error)
                            }
                        } catch (restoreError: Throwable) {
                            if (restoreError !== error) error.addSuppressed(restoreError)
                            release { restored?.close() }
                            admission.withLock { mutableState.value = previousState.copy(phase = ProfileHostPhase.RecoveryRequired, failure = error) }
                        }
                    }
                }
                throw error
            } finally {
                if (!recoveryResumed) withContext(NonCancellable) { recovery?.activation?.session?.discardPreparedSwitch() }
            }
        }
    }

    override suspend fun close() {
        outsideCall()
        command.withLock {
            val jobs = admission.withLock {
                if (mutableState.value.phase == ProfileHostPhase.Closed) null else {
                    mutableState.value = mutableState.value.copy(phase = ProfileHostPhase.Closed)
                    view.value = null
                    calls.keys.toList()
                }
            }
            if (jobs == null) { withContext(NonCancellable) { closeCompletion.await() }; return@withLock }
            withContext(NonCancellable + Transition(this)) {
                var failure: Throwable? = null
                suspend fun release(action: suspend () -> Unit) {
                    try { action() } catch (error: Throwable) {
                        if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                    }
                }
                jobs.forEach { it.cancel() }
                jobs.forEach { it.join() }
                release { finishOverlays() }
                val old = admission.withLock { current.also { current = null } }
                release { old?.close() }
                if (failure == null) closeCompletion.complete(Unit) else closeCompletion.completeExceptionally(failure!!)
                failure?.let { throw it }
            }
        }
    }
}
