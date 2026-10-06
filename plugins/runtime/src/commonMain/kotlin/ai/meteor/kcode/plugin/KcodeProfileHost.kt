package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableImport
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleImport
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveImport
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.profiles.ProfileManagement
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRecoveryReview
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRepairRequest
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRepairResult
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
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.Json

/** Preparation verifies locked metadata/code; create allocates a fresh, unpublished runtime. */
class PreparedProfileRuntime(
    val activation: ProfileActivation,
    val create: suspend () -> KcodeAgentRuntime,
)

fun interface ProfileRuntimeFactory {
    suspend fun prepare(id: String): PreparedProfileRuntime
    suspend fun prepare(request: ProfileActivationRequest): PreparedProfileRuntime =
        error("This factory does not support explicit Profile activation")
}

enum class ProfileHostPhase { Ready, Preparing, Switching, RecoveryRequired, Closed }

data class ProfileHostState(
    val profileId: String,
    val phase: ProfileHostPhase = ProfileHostPhase.Ready,
    val failure: Throwable? = null,
)

/** Stable native facades own task admission across replacement of complete product runtimes. */
class KcodeProfileHost(
    initial: KcodeAgentRuntime?,
    initialProfileId: String,
    private val factory: ProfileRuntimeFactory,
    private val management: ProfileManagement? = null,
    private val commandGateway: ProfileCommandGateway? = null,
    initialFailure: Throwable? = null,
    private val overlayAvailable: Boolean = initial?.conversationOverlayController != null,
    private val templateProvider: () -> List<ProfileDefinition> = { emptyList() },
) : AgentRuntimeOwner, ApplicationContent {
    init {
        require(initial != null || initialFailure != null || management != null && commandGateway != null) {
            "A Profile host needs a runtime, a startup failure, or a host-owned management client"
        }
    }
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
    private val pendingRetirement = mutableListOf<KcodeAgentRuntime>()
    private val mutableState = MutableStateFlow(ProfileHostState(
        initialProfileId,
        if (initial == null) ProfileHostPhase.RecoveryRequired else ProfileHostPhase.Ready,
        initialFailure,
    ))
    private var startupRetirement: AgentRuntimeOwner? = null
    private fun templateSnapshot(): Pair<List<ProfileDefinition>, Exception?> {
        return try {
            val definitions = templateProvider().map {
                Json.decodeFromString(ProfileDefinition.serializer(), Json.encodeToString(ProfileDefinition.serializer(), it))
                    .also(ProfileDefinition::validate)
            }
            require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate Profile template IDs" }
            definitions to null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emptyList<ProfileDefinition>() to error
        }
    }
    val profileTemplates: List<ProfileDefinition> get() = templateSnapshot().first
    val profileTemplateFailure: Exception? get() = templateSnapshot().second
    val state: StateFlow<ProfileHostState> = mutableState.asStateFlow()
    val profileCommands: ProfileManagementClient? get() = commandGateway
    private var foreground = true
    private var overlayClosed = false
    private val closeCompletion = CompletableDeferred<Unit>()

    /** Compatibility entry points require a running product rather than a recovery host. */
    suspend fun requireInitialRuntime(): KcodeAgentRuntime {
        val failure = state.value.failure
        if (state.value.phase != ProfileHostPhase.Ready) {
            val error = failure ?: IllegalStateException("Profile startup did not produce a ready runtime")
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
        return runtime
    }

    private suspend fun outsideCall() {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        (current?.owner as? KcodePluginRuntime)?.requireOutsideExecution()
        val context = currentCoroutineContext()
        check(context[HostCall]?.host !== this && context[Transition]?.host !== this) {
            "A Profile callback cannot switch or close its own host"
        }
    }

    private suspend fun <T> admitted(allowRecovery: Boolean = false, block: suspend () -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job])
        admission.withLock {
            val phase = mutableState.value.phase
            check(phase == ProfileHostPhase.Ready || allowRecovery && phase == ProfileHostPhase.RecoveryRequired) {
                "Profile host does not admit work: $phase"
            }
            calls[job] = (calls[job] ?: 0) + 1
        }
        try { return withContext(HostCall(this)) { block() } }
        finally {
            withContext(NonCancellable) {
                admission.withLock {
                    val remaining = calls.getValue(job) - 1
                    if (remaining == 0) calls.remove(job) else calls[job] = remaining
                }
            }
        }
    }

    private suspend fun <T> call(block: suspend (KcodeAgentRuntime) -> T): T = admitted {
        val runtime = checkNotNull(current)
        try { block(runtime) }
        catch (failure: ProfileRuntimeRecoveryException) {
            withContext(NonCancellable) {
                admission.withLock {
                    if (current === runtime && mutableState.value.phase != ProfileHostPhase.Closed) {
                        current = null
                        view.value = null
                        if (runtime !in pendingRetirement) pendingRetirement += runtime
                        mutableState.value = mutableState.value.copy(
                            phase = ProfileHostPhase.RecoveryRequired,
                            failure = failure,
                        )
                    }
                }
            }
            throw failure
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
        override suspend fun profileCatalogue() = metadata { it.catalogue().withActive() }
        override suspend fun profileDraft(id: String) = metadata { it.draft(id) }
        override suspend fun writeProfileDraft(write: ProfileDraftWrite) = metadata { it.write(write).withActive() }
        override suspend fun cloneProfile(request: ProfileCloneRequest) = metadata { it.clone(request).withActive() }
        override suspend fun deleteProfile(id: String, expectedRevision: Long) = metadata {
            require(id != state.value.profileId) { "Cannot delete the active Profile" }
            it.remove(id, expectedRevision).withActive()
        }
        override suspend fun previewProfile(target: ProfileTarget) = metadata { it.preview(target) }
        override suspend fun profileHistory(id: String) = metadata { it.history(id) }
        override suspend fun activateProfile(request: ProfileActivationRequest, cancelActive: Boolean): ProfileCompositionState {
            checkNotNull(management) { "Profile management is unavailable" }
            request.target.validate()
            return switchInternal(request.target.profileId, cancelActive, request, allowRecovery = true)
        }
        override suspend fun currentProfile() = call { checkNotNull(it.pluginManager).currentProfile() }
        override suspend fun editProfile(edit: ProfileCompositionEdit) =
            call { checkNotNull(it.pluginManager).editProfile(edit) }
        override suspend fun importPackages(packages: List<PluginPackageImport>) = call { checkNotNull(it.pluginManager).importPackages(packages) }
        override suspend fun applyChanges(changes: PluginCompositionChange) = call { checkNotNull(it.pluginManager).applyChanges(changes) }
        override suspend fun install(spec: DynamicPluginSpec) = call { checkNotNull(it.pluginManager).install(spec) }
        override suspend fun replace(spec: DynamicPluginSpec) = call { checkNotNull(it.pluginManager).replace(spec) }
        override suspend fun uninstall(id: String) = call { checkNotNull(it.pluginManager).uninstall(id) }
        override suspend fun installed(): List<DynamicPluginSpec> = call { checkNotNull(it.pluginManager).installed() }
        override suspend fun setEnabled(id: String, enabled: Boolean) = call { checkNotNull(it.pluginManager).setEnabled(id, enabled) }
    }

    private fun ProfileCatalogue.withActive(): ProfileCatalogue = copy(
        activeProfileId = if (state.value.phase == ProfileHostPhase.Ready) state.value.profileId else null,
    )
    private suspend fun <T> metadata(block: suspend (ProfileManagement) -> T): T = admitted(allowRecovery = true) {
        block(checkNotNull(management) { "Profile management is unavailable" })
    }

    suspend fun inspectRepositoryRecovery(): ProfileRepositoryRecoveryReview? = metadata {
        check(state.value.phase == ProfileHostPhase.RecoveryRequired) { "Repository recovery requires an inactive product" }
        it.inspectRepositoryRecovery()
    }

    suspend fun prepareRecoveryMetadata() = metadata {
        check(state.value.phase == ProfileHostPhase.RecoveryRequired) { "Recovery metadata requires an inactive product" }
        it.prepareRecoveryMetadata()
    }

    /** Native recovery is explicit metadata repair; it never selects or allocates a product. */
    suspend fun repairRepository(request: ProfileRepositoryRepairRequest): ProfileRepositoryRepairResult {
        outsideCall()
        return command.withLock {
            metadata {
                check(state.value.phase == ProfileHostPhase.RecoveryRequired) { "Repository recovery requires an inactive product" }
                it.repairRepository(request)
            }
        }
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
        conversationOverlayController = if (overlayAvailable) conversationOverlayController else null,
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

    suspend fun selectProfileModule(packageId: String, moduleId: String, expected: ProfileCompositionState): ProfileCompositionState = call {
        checkNotNull(it.owner as? KcodePluginRuntime) { "Native module selection is unavailable" }
            .selectProfileModule(packageId, moduleId, expected)
    }

    suspend fun profileModules() = call {
        checkNotNull(it.owner as? KcodePluginRuntime) { "Native module catalogue is unavailable" }.profileModuleCatalogue()
    }

    suspend fun importPortableProfile(request: ProfilePortableImport): ProfileCatalogue = metadata {
        it.importPortable(request.document, request.id, request.displayName, request.expectedRevision).withActive()
    }

    suspend fun importProfileBundles(request: ProfileBundleImport): ProfileCatalogue = metadata {
        it.importBundles(request).withActive()
    }

    suspend fun exportPortableProfile(request: ProfilePortableExport): String = metadata {
        it.exportPortable(request.target, expectedRevision = request.expectedRevision)
    }

    suspend fun importProfileArchive(request: ProfileArchiveImport): ProfileCatalogue = metadata {
        it.importArchive(request).withActive()
    }

    suspend fun exportProfileArchive(request: ProfilePortableExport, consume: suspend (ProfileArchiveReference) -> Unit): Unit = metadata {
        it.exportArchive(request, consume)
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
        switchInternal(id, cancelActive, null)
    }

    /** Host-owned recovery works even when the product tree and its UI have been withdrawn. */
    suspend fun recoverTo(id: String): ProfileCompositionState =
        switchInternal(id, false, null, allowRecovery = true, recoveryOnly = true)

    private suspend fun releaseRuntime(runtime: KcodeAgentRuntime) {
        try {
            runtime.close()
            pendingRetirement.remove(runtime)
        } catch (error: Throwable) {
            if (runtime !in pendingRetirement) pendingRetirement += runtime
            throw error
        }
    }

    private suspend fun recoverInternal(id: String, request: ProfileActivationRequest?): ProfileCompositionState {
        val previous = admission.withLock {
            check(calls.isEmpty() && overlayTurns.isEmpty()) { "Finish recovery metadata calls before activating a Profile" }
            mutableState.value.also { mutableState.value = it.copy(phase = ProfileHostPhase.Preparing) }
        }
        var target: PreparedProfileRuntime? = null
        var candidate: KcodeAgentRuntime? = null
        var published = false
        try {
            withContext(Transition(this)) {
                target = if (request == null) factory.prepare(id) else factory.prepare(request)
                check(target!!.activation.resolved.definition.id == id) { "Prepared Profile identity mismatch" }
                target!!.activation.session.requirePreparedSwitch()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    // Never overlap a new owner with resources whose retirement failed.
                    startupRetirement?.let { owner -> owner.close(); startupRetirement = null }
                    pendingRetirement.toList().forEach { releaseRuntime(it) }
                    admission.withLock { mutableState.value = previous.copy(phase = ProfileHostPhase.Switching) }
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
                    (candidate!!.owner as? KcodePluginRuntime)?.publishProductExecution()
                    recordProfileCommandPublication(target!!.activation.session.currentCompositionState())
                }
            }
        } catch (error: Throwable) {
            if (!published) withContext(NonCancellable + Transition(this)) {
                try { candidate?.let { releaseRuntime(it) } }
                catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                admission.withLock { mutableState.value = previous.copy(failure = error) }
            }
            throw error
        } finally {
            if (!published) withContext(NonCancellable) { target?.activation?.session?.discardPreparedSwitch() }
        }
        return withContext(NonCancellable) { checkNotNull(target).activation.session.currentCompositionState() }
    }

    private suspend fun switchInternal(id: String, cancelActive: Boolean, request: ProfileActivationRequest?,
        allowRecovery: Boolean = false, recoveryOnly: Boolean = false): ProfileCompositionState {
        outsideCall()
        return command.withLock {
            if (recoveryOnly) check(state.value.phase == ProfileHostPhase.RecoveryRequired) { "Profile host does not require recovery" }
            if (allowRecovery && state.value.phase == ProfileHostPhase.RecoveryRequired) {
                return@withLock recoverInternal(id, request)
            }
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
            val previousRuntime = current
            var executionPaused = false
            try {
                withContext(Transition(this)) {
                    (previousRuntime?.owner as? KcodePluginRuntime)?.let { owner ->
                        owner.pauseProductExecution(cancelActive)
                        executionPaused = true
                    }
                    if (cancelActive) withContext(NonCancellable) {
                        val jobs = admission.withLock { calls.keys.toList() }
                        jobs.forEach { it.cancel() }
                        jobs.forEach { it.join() }
                        finishOverlays()
                    }
                    admission.withLock {
                        check(mutableState.value.phase == ProfileHostPhase.Preparing && current != null) {
                            "Active composition failed while switching Profiles"
                        }
                    }
                    val previousIntent = current?.pluginManager?.currentProfile()
                    target = if (request == null) factory.prepare(id) else factory.prepare(request)
                    check(target!!.activation.resolved.definition.id == id) { "Prepared Profile identity mismatch" }
                    target!!.activation.session.requirePreparedSwitch()
                    recovery = factory.prepare(previousState.profileId)
                    check(recovery!!.activation.resolved.definition.id == previousState.profileId) { "Recovery Profile identity mismatch" }
                    recovery!!.activation.session.requirePreparedSwitch()
                    if (previousIntent != null) check(recovery!!.activation.session.currentCompositionState() == previousIntent) {
                        "Active Profile changed outside its runtime; refresh before switching"
                    }
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
                        releaseRuntime(old)
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
                        (candidate!!.owner as? KcodePluginRuntime)?.publishProductExecution()
                        recordProfileCommandPublication(target!!.activation.session.currentCompositionState())
                    }
                }
            } catch (error: Throwable) {
                if (published) throw error
                withContext(NonCancellable + Transition(this)) {
                    suspend fun release(action: suspend () -> Unit) {
                        try { action() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    }
                    release { candidate?.let { releaseRuntime(it) } }
                    release { target?.activation?.session?.discardPreparedSwitch() }
                    if (!withdrawn) admission.withLock {
                        val phase = if (
                            current == null || pendingRetirement.isNotEmpty() ||
                            mutableState.value.phase == ProfileHostPhase.RecoveryRequired
                        ) {
                            ProfileHostPhase.RecoveryRequired
                        } else {
                            ProfileHostPhase.Ready
                        }
                        mutableState.value = previousState.copy(phase = phase, failure = error)
                    }
                    else if (!previousReleased || pendingRetirement.isNotEmpty()) admission.withLock {
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
                            (restored.owner as? KcodePluginRuntime)?.publishProductExecution()
                        } catch (restoreError: Throwable) {
                            if (restoreError !== error) error.addSuppressed(restoreError)
                            release { restored?.let { releaseRuntime(it) } }
                            admission.withLock { mutableState.value = previousState.copy(phase = ProfileHostPhase.RecoveryRequired, failure = error) }
                        }
                    }
                }
                throw error
            } finally {
                if (executionPaused && current === previousRuntime && mutableState.value.phase == ProfileHostPhase.Ready) {
                    withContext(NonCancellable) {
                        (previousRuntime?.owner as? KcodePluginRuntime)?.resumeProductExecution()
                    }
                }
                if (!recoveryResumed) withContext(NonCancellable) { recovery?.activation?.session?.discardPreparedSwitch() }
            }
            withContext(NonCancellable) { checkNotNull(target).activation.session.currentCompositionState() }
        }
    }

    override suspend fun close() {
        outsideCall()
        commandGateway?.close()
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
                release { startupRetirement?.close() }
                val old = admission.withLock { current.also { current = null } }
                release { old?.let { releaseRuntime(it) } }
                pendingRetirement.toList().filterNot { it === old }.forEach { retired ->
                    release { releaseRuntime(retired) }
                }
                if (failure == null) closeCompletion.complete(Unit) else closeCompletion.completeExceptionally(failure!!)
                failure?.let { throw it }
            }
        }
    }

    companion object {
        /** Initial Profile failure leaves host-owned management available without a product tree. */
        suspend fun start(
            initialProfileId: () -> String,
            factory: ProfileRuntimeFactory,
            management: ProfileManagement,
            commands: ProfileCommandGateway,
            overlayAvailable: Boolean = false,
            canRecover: () -> Boolean = { true },
            templates: () -> List<ProfileDefinition> = { emptyList() },
            createInitial: suspend () -> KcodeAgentRuntime,
        ): KcodeProfileHost {
            val initial = try { createInitial() }
            catch (error: Throwable) {
                if (error is CancellationException || !canRecover() || currentCoroutineContext()[Job]?.isActive == false) {
                    withContext(NonCancellable) { commands.close() }
                    throw error
                }
                return KcodeProfileHost(null, initialProfileId(), factory, management, commands,
                    initialFailure = error, overlayAvailable = overlayAvailable, templateProvider = templates).also { host ->
                    host.startupRetirement = (error as? RuntimeStartupRetirementException)?.retirement
                    commands.bind(host)
                }
            }
            try { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { initial.close() }.exceptionOrNull()?.takeIf { it !== cancelled }?.let(cancelled::addSuppressed)
                    commands.close()
                }
                throw cancelled
            }
            try {
                return withContext(NonCancellable) {
                    KcodeProfileHost(initial, initialProfileId(), factory, management, commands,
                        overlayAvailable = overlayAvailable, templateProvider = templates).also { host ->
                        commands.bind(host)
                        (initial.owner as? KcodePluginRuntime)?.publishProductExecution()
                    }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { initial.close() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                    runCatching { commands.close() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                }
                throw error
            }
        }

        /** Constructs the standalone manager without starting the selected product composition. */
        suspend fun startManagementOnly(
            initialProfileId: () -> String,
            factory: ProfileRuntimeFactory,
            management: ProfileManagement,
            commands: ProfileCommandGateway,
            templates: () -> List<ProfileDefinition> = { emptyList() },
        ): KcodeProfileHost {
            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()
            val host = try {
                withContext(NonCancellable) {
                    KcodeProfileHost(
                        initial = null,
                        initialProfileId = initialProfileId(),
                        factory = factory,
                        management = management,
                        commandGateway = commands,
                        overlayAvailable = false,
                        templateProvider = templates,
                    ).also(commands::bind)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { commands.close() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                }
                throw error
            }
            try {
                callerContext.ensureActive()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { host.close() }.exceptionOrNull()?.takeIf { it !== cancelled }?.let(cancelled::addSuppressed)
                }
                throw cancelled
            }
            return host
        }
    }
}
