package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.SettingsStoreFactory

import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.plugin.api.ConversationOverlayHostState
import ai.meteor.kcode.plugin.api.KcodeConversationOverlays
import ai.meteor.kcode.plugin.api.ConversationOverlayFactory
import ai.meteor.kcode.AgentRuntimeOwner
import ai.meteor.kcode.ApplicationContent
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.ArtifactFileStoreFactory
import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.KcodeConversationCommands
import ai.meteor.kcode.chat.ConversationCommandSnapshot
import ai.meteor.kcode.plugin.api.ApplicationFrame
import ai.meteor.kcode.plugin.api.ApplicationServices
import org.cordis.ServiceKey
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.chat.UnavailableScheduledTasks
import ai.meteor.kcode.plugin.api.KcodeConversationExport
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodePluginInstallations
import ai.meteor.kcode.plugin.api.PluginCompositionStore
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.api.validatePluginApi
import ai.meteor.kcode.plugin.api.validatePackageDependencies
import ai.meteor.kcode.plugin.api.KcodePluginInventory
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.KcodeSystemPrompt
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeUiContributions
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Fiber
import org.cordis.FiberState
import org.cordis.Plugin
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin

interface DynamicPluginController : AgentPluginManager {
    /** Imports and validates a not-yet-installed artifact without applying or publishing it. */
    suspend fun validateCandidate(spec: DynamicPluginSpec): Unit =
        error("This controller does not support candidate validation")
    suspend fun close()
    suspend fun settle()
}

fun interface DynamicPluginControllerFactory {
    fun create(context: Context, loader: Loader, inventory: KcodePluginInventory): DynamicPluginController
}

/** A trusted release offered by the native distribution, with a stable composition identity. */
data class BundledPluginPackage(val id: String, val release: PluginPackageImport)

data class KcodePluginRuntimeConfig(
    val interactionPolicy: InteractionPolicy,
    val hostInputs: PluginHostInputs? = null,
    val settingsBackedInteraction: Boolean = false,
    val skillRuntime: SkillRuntime? = null,
    val featurePlugins: List<KcodePluginMount> = emptyList(),
    val dynamicPluginControllerFactory: DynamicPluginControllerFactory? = null,
    val profile: KcodePluginProfile = KcodePluginProfile(),
    val settingsStore: AppSettingsStore? = null,
    val settingsStoreFactory: SettingsStoreFactory? = null,
    val historyRepositoryFactory: HistoryRepositoryFactory? = null,
    val historyRepository: ConversationHistoryRepository? = null,
    val artifactRepository: ArtifactRepository? = null,
    val webContainerController: WebContainerController? = null,
    val bundle: List<KcodePluginMount>? = null,
    val pluginCompositionStore: PluginCompositionStore? = null,
    val builtinAliases: Map<String, Set<String>>? = null,
    val artifactFileStore: ArtifactFileStore? = null,
    val artifactFileStoreFactory: ArtifactFileStoreFactory? = null,
    val conversationImageSaverFactory: ConversationImageSaverFactory? = null,
    val scheduledTaskNotificationsFactory: ScheduledTaskNotificationsFactory? = null,
    val conversationOverlayFactory: (suspend (StateFlow<UiContributionsSnapshot>) -> AgentConversationOverlayController?)? = null,
    val bundledPackages: List<BundledPluginPackage> = emptyList(),
)

/** Only inventory and the loader bridge are bootstrap infrastructure. Product providers are managed. */
class KcodePluginRuntime private constructor(
    private val context: Context,
    private val hostInputs: PluginHostInputs?,
    private val bootstrap: List<Fiber<*>>,
    private val records: LinkedHashMap<String, ManagedPlugin>,
    val inventory: KcodePluginInventory,
    private val external: DynamicPluginController?,
    private val builtinAliases: Map<String, Set<String>>,
    private val overlayHostState: ConversationOverlayHostState,
    private val overlayUiSlots: MutableStateFlow<UiContributionsSnapshot>,
) : AgentRuntimeOwner, ApplicationContent {
    private class ManagedPlugin(
        var mount: KcodePluginMount,
        var enabled: Boolean,
        var fiber: Fiber<*>? = null,
    )

    private data class ApplicationView(
        val renderer: ApplicationRenderer,
        val frame: ApplicationFrame,
    )

    private data class PreparedApplicationView(
        val view: ApplicationView?,
        val uiSlots: UiContributionsSnapshot,
        val modelCatalog: ModelCatalogSnapshot,
    )

    private class ClosingRuntime(val runtime: KcodePluginRuntime) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClosingRuntime>
    }

    private data class ClosePlan(val turns: List<Job>, val fibers: List<Fiber<*>>)

    private val closeCompletion = CompletableDeferred<Unit>()
    private val lock = Mutex()
    private val turns = mutableMapOf<Job, Int>()
    private var closed = false
    private var restoring = false
    private val bundledReleaseHistory = linkedMapOf<String, String>()
    private var committedModelCatalog = ModelCatalogSnapshot()
    override suspend fun updateSettings(update: SettingsUpdate): AppliedSettingsUpdate {
        val (handler, catalog) = lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            val commands = checkNotNull(context[KcodeSettingsCommands.Key]) { "Enable settings and settings commands before configuring settings" }
            commands.handler to committedModelCatalog
        }
        return handler.apply(update, catalog)
    }

    override suspend fun modelCatalog(): ModelCatalogSnapshot = lock.withLock {
        check(!closed) { "plugin runtime is closed" }
        committedModelCatalog
    }

    private val applicationView = MutableStateFlow<ApplicationView?>(null)

    /** Legacy host projection follows the current plugin; it never retains a native repository. */
    val artifactRepository: ArtifactRepository = object : ArtifactRepository {
        override suspend fun list(): List<Artifact> {
            val repository = lock.withLock {
                check(!closed) { "plugin runtime is closed" }
                context.require(KcodeArtifacts.Key).repository
            }
            return repository.list()
        }
    }

    /** Host operations use the current Web provider and reject absence or runtime closure. */
    val webContainerController: WebContainerController = CurrentWebContainerController {
        lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            requireNotNull(context.require(KcodeWebContainers.Key).controller) { "Web containers are unavailable" }
        }
    }

    val conversationOverlayController: AgentConversationOverlayController =
        object : AgentConversationOverlayController {
            override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn {
                val service = lock.withLock {
                    check(!closed) { "plugin runtime is closed" }
                    context.require(KcodeConversationOverlays.Key)
                }
                return requireNotNull(service.startTurn(initialMessages)) { "Conversation overlays are unavailable" }
            }
            override suspend fun setHostForeground(isForeground: Boolean) {
                val registry = lock.withLock {
                    check(!closed) { "plugin runtime is closed" }
                    overlayHostState.setForeground(isForeground)
                    context[KcodeConversationOverlays.Key]
                }
                registry?.setHostForeground(isForeground)
            }
            override suspend fun close() {
                val controller = lock.withLock {
                    check(!closed) { "plugin runtime is closed" }
                    context[KcodeConversationOverlays.Key]
                }?.current()
                controller?.close()
            }
        }

    /** Stable host facade: resolve the active agents provider at every new turn. */
    val chatService: ChatService = object : ChatService {
        override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String =
            useAgent { it.reply(configuration, history, prompt) }

        override suspend fun replyStreaming(
            configuration: ModelConfiguration,
            history: List<ChatMessage>,
            prompt: String,
            goalSession: GoalSession?,
            scheduledTaskSession: ScheduledTaskSession?,
            scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
            onToolUse: suspend (ToolUseEvent) -> Unit,
            onSubAgent: suspend (SubAgentEvent) -> Unit,
            onDelta: suspend (String) -> Unit,
        ): String = useAgent {
            it.replyStreaming(
                configuration, history, prompt, goalSession, scheduledTaskSession,
                scheduledTaskCompletionSession, onToolUse, onSubAgent, onDelta,
            )
        }
    }

    /** Manages built-ins as well as external artifacts. */
    val pluginManager: DynamicPluginController = object : DynamicPluginController {
        override suspend fun importPackages(packages: List<PluginPackageImport>) {
            var specs = emptyList<DynamicPluginSpec>()
            mutate(prepare = {
                require(packages.isNotEmpty()) { "No plugin packages supplied" }
                specs = context.require(KcodePluginPackages.Key).resolver.resolve(packages, dynamic().installed())
            }) {
                applyCompositionChange(PluginCompositionChange(upserts = specs))
            }
        }

        override suspend fun applyChanges(changes: PluginCompositionChange) = mutate {
            applyCompositionChange(changes)
        }

        override suspend fun install(spec: DynamicPluginSpec) = mutate {
            validateInstallation(spec)
            require(spec.id !in records) { "plugin '${spec.id}' is configured; use replace()" }
            validatePackageDependencies(dynamic().installed() + spec)
            dynamic().install(spec)
        }

        override suspend fun replace(spec: DynamicPluginSpec) = mutate {
            validateInstallation(spec)
            validatePackageDependencies(dynamic().installed().filterNot { it.id == spec.id } + spec)
            val record = records[spec.id]
            if (record == null || dynamic().installed().any { it.id == spec.id }) {
                dynamic().replace(spec)
            } else {
                dynamic().validateCandidate(spec)
                val wasEnabled = record.enabled
                detach(record)
                inventory.remove(spec.id)
                try {
                    dynamic().install(spec)
                } catch (error: Throwable) {
                    restore(spec.id, record, wasEnabled, error)
                    throw error
                }
            }
        }

        override suspend fun uninstall(id: String) = mutate {
            validatePackageDependencies(external?.installed().orEmpty().filterNot { it.id == id })
            if (external?.installed()?.any { it.id == id } == true) {
                external.uninstall(id)
                records[id]?.let { publish(id, it) }
            } else {
                val record = records[id] ?: error("plugin '$id' is not configured")
                detach(record)
                publish(id, record)
            }
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) = mutate {
            if (!restoring) validatePackageDependencies(external?.installed().orEmpty().map { if (it.id == id) it.copy(enabled = enabled) else it })
            if (external?.installed()?.any { it.id == id } == true) {
                if (enabled) validateInstallation(external.installed().single { it.id == id })
                external.setEnabled(id, enabled)
            } else {
                val record = records[id] ?: error("plugin '$id' is not configured")
                if (record.enabled != enabled) {
                    if (enabled) {
                        record.enabled = true
                        try {
                            record.fiber = record.mount.mount(context)
                            record.fiber?.await()
                        } catch (error: Throwable) {
                            runCatching { detach(record) }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                            throw error
                        }
                    } else {
                        detach(record)
                    }
                }
            }
        }

        override suspend fun installed(): List<DynamicPluginSpec> = lock.withLock { external?.installed().orEmpty() }
        override suspend fun close() = this@KcodePluginRuntime.close()
        override suspend fun settle() = lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            this@KcodePluginRuntime.settle()
        }
    }

    val dynamicPlugins: DynamicPluginController? get() = if (external == null) null else pluginManager

    private suspend fun applyCompositionChange(changes: PluginCompositionChange) {
        require(changes.upserts.map { it.id }.distinct().size == changes.upserts.size) { "Duplicate composition upsert" }
        require(changes.upserts.none { it.id in changes.removals } && changes.enabled.keys.none { it in changes.removals }) { "Conflicting composition changes" }
        val installed = dynamic().installed()
        val current = installed.associateBy { it.id }
        require(changes.removals.all { it in current }) { "Only external plugins can be removed in a transaction" }
        require(changes.enabled.keys.all { it in current || it in records || changes.upserts.any { spec -> spec.id == it } }) { "Unknown plugin enable change" }
        changes.upserts.forEach { spec ->
            validateInstallation(spec)
            current[spec.id]?.let { previous ->
                val candidateRelease = spec.packageInstallation
                val previousRelease = previous.packageInstallation
                if (candidateRelease != null && previousRelease != null && previous.version == spec.version) {
                    require(previousRelease.archiveSha256 == candidateRelease.archiveSha256) { "A package release cannot be republished with different bytes" }
                }
            }
        }
        val final = current.toMutableMap()
        changes.removals.forEach(final::remove)
        changes.upserts.forEach { final[it.id] = it }
        changes.enabled.forEach { (id, enabled) -> final[id]?.let { final[id] = it.copy(enabled = enabled) } }
        validatePackageDependencies(final.values.toList())
        PluginCompositionSnapshot(external = final.values.map(StoredDynamicPlugin::from)).validate()

        // Package availability is independent of Loader class linkage and Cordis inject ordering.
        // Remove dependants before dependencies; final snapshot validation rejects dangling links.
        installed.asReversed().filter { it.id in changes.removals }.forEach { dynamic().uninstall(it.id) }
        val upsertIds = changes.upserts.map { it.id }.toSet()
        val ordered = PluginCompositionSnapshot(external = final.values.map(StoredDynamicPlugin::from)).orderedExternal()
        ordered.filter { it.id in upsertIds }.forEach { candidate ->
            val spec = final.getValue(candidate.id)
            val record = records[spec.id]
            if (spec.id in current) {
                dynamic().replace(spec)
                dynamic().setEnabled(spec.id, spec.enabled)
            } else {
                if (record != null) {
                    dynamic().validateCandidate(spec)
                    detach(record)
                    inventory.remove(spec.id)
                }
                dynamic().install(spec)
            }
        }
        changes.enabled.forEach { (id, enabled) ->
            if (id in final) dynamic().setEnabled(id, enabled)
            else {
                val record = records.getValue(id)
                if (record.enabled != enabled) {
                    if (enabled) {
                        record.enabled = true
                        record.fiber = record.mount.mount(context)
                        record.fiber?.await()
                    } else detach(record)
                }
            }
        }
    }

    /** Typed in-process replacement. Restore the previous implementation if apply fails. */
    suspend fun replacePlugin(replacement: KcodePluginMount) = mutate {
        val id = replacement.descriptor.id
        require(external?.installed()?.none { it.id == id } != false) { "plugin '$id' is an external artifact" }
        val record = records[id] ?: error("plugin '$id' is not configured")
        val previous = record.mount
        val wasEnabled = record.enabled
        detach(record)
        record.mount = replacement
        record.enabled = wasEnabled
        try {
            if (wasEnabled) {
                record.fiber = replacement.mount(context)
                record.fiber?.await()
            }
        } catch (error: Throwable) {
            runCatching { detach(record) }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
            record.mount = previous
            restore(id, record, wasEnabled, error)
            throw error
        }
    }

    suspend fun diagnostics(): KcodePluginDiagnostics = lock.withLock {
        if (!closed) settle()
        KcodePluginDiagnostics(
            plugins = inventory.snapshot(),
            toolContributions = context[KcodeTools.Key]?.contributionIds().orEmpty(),
            promptSections = context[KcodeSystemPrompt.Key]?.sectionIds().orEmpty(),
            modelAdapters = context[KcodeLlm.Key]?.adapterIds().orEmpty(),
        )
    }

    @Composable
    override fun Render(options: ApplicationHostOptions) {
        val view by applicationView.collectAsState()
        val active = view ?: return
        key(active.renderer) {
            active.frame.Render(options)
        }
    }

    private fun dynamic(): DynamicPluginController = checkNotNull(external) { "this host has no dynamic artifact loader" }

    private suspend fun <T> useAgent(block: suspend (ChatService) -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val service = lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            settle()
            context.require(KcodeAgents.Key).chatService.also { turns[job] = (turns[job] ?: 0) + 1 }
        }
        try {
            return block(service)
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    val count = turns.getValue(job) - 1
                    if (count == 0) turns.remove(job) else turns[job] = count
                }
            }
        }
    }

    private data class RuntimeCheckpoint(
        val mounts: Map<String, Pair<KcodePluginMount, Boolean>>,
        val external: List<DynamicPluginSpec>,
    )

    private suspend fun validateInstallation(spec: DynamicPluginSpec) {
        spec.validatePluginApi()
        if (spec.packageInstallation != null) context.require(KcodePluginPackages.Key).resolver.verify(spec)
        if (context[KcodePluginInstallations.Key]?.store != null) StoredDynamicPlugin.from(spec)
    }

    private suspend fun checkpoint(): RuntimeCheckpoint = RuntimeCheckpoint(
        records.mapValues { (_, record) -> record.mount to record.enabled },
        external?.installed().orEmpty(),
    )

    private suspend fun compositionSnapshot(): PluginCompositionSnapshot = PluginCompositionSnapshot(
        builtinsEnabled = records.mapValues { it.value.enabled },
        external = external?.installed().orEmpty().map(StoredDynamicPlugin::from),
        bundledPackages = bundledReleaseHistory.toMap(),
    )

    /** Restore interfaces and registrations, not arbitrary provider instance state. */
    private suspend fun rollback(checkpoint: RuntimeCheckpoint) {
        external?.installed().orEmpty().asReversed().forEach { external?.uninstall(it.id) }
        records.values.toList().asReversed().forEach { detach(it) }
        checkpoint.mounts.forEach { (id, previous) ->
            val record = records.getValue(id)
            record.mount = previous.first
            record.enabled = previous.second
            if (record.enabled) {
                record.fiber = record.mount.mount(context)
                record.fiber?.await()
            }
        }
        // Recorded insertion order is dependency order: install refuses missing loader dependencies.
        checkpoint.external.forEach { spec ->
            inventory.remove(spec.id)
            dynamic().install(spec)
        }
        settle(publishView = false)
    }

    private suspend fun mutate(prepare: suspend () -> Unit = {}, block: suspend () -> Unit) {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
            // Archive preparation is cancellable and cannot modify the composition.
            prepare()
            withContext(NonCancellable) {
                val storeBefore = context[KcodePluginInstallations.Key]?.store
                val before = if (!restoring) checkpoint() else null
                var candidateApplied = false
                try {
                    block()
                    candidateApplied = true
                    settle(publishView = false)
                    if (external?.installed().orEmpty().any { it.packageInstallation != null }) {
                        context.require(KcodePluginPackages.Key)
                    }
                    if (!restoring) {
                        val prepared = prepareApplicationView()
                        val store = context[KcodePluginInstallations.Key]?.store ?: storeBefore
                        if (store != null) {
                            store.save(compositionSnapshot())
                        }
                        commitApplicationView(prepared)
                    }
                } catch (error: Throwable) {
                    if (before != null && (candidateApplied || checkpoint() != before)) {
                        runCatching { rollback(before) }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                    }
                    runCatching { settle(publishView = !restoring) }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                    throw error
                }
            }
        }
    }

    private suspend fun restoreInstalledComposition(bundled: List<BundledPluginPackage>, disabled: Set<String>) {
        val store = context[KcodePluginInstallations.Key]?.store
        require(bundled.isEmpty() || store != null) { "Bundled packages require a composition store" }
        if (store == null) return
        val loaded = store.load().also { it.validateForRestore() }
        val enabled = loaded.builtinsEnabled.toMutableMap()
        val installed = loaded.external.map { it.toSpec() }.associateBy { it.id }.toMutableMap()
        val retired = mutableSetOf<String>()
        val inherited = mutableMapOf<String, Boolean>()
        val retiringCandidates = builtinAliases.keys.filterTo(mutableSetOf()) { id ->
            val offered = loaded.bundledPackages[id]
            offered != null && installed[id]?.packageInstallation?.archiveSha256 == offered
        }
        // Preserve the verified dependency closure of every surviving release. Package and
        // class-loader identities cannot be rewritten to an aggregate without re-resolution.
        val required = mutableSetOf<String>()
        fun retainDependencies(id: String) {
            val spec = installed[id] ?: return
            (spec.dependencies + spec.packageInstallation?.dependencies.orEmpty().keys).forEach { dependency ->
                if (required.add(dependency)) retainDependencies(dependency)
            }
        }
        installed.keys.filterNot { it in retiringCandidates }.forEach(::retainDependencies)
        // Earlier feature releases relied on service injection between independently
        // offered packages, without archive dependency locks. Preserve the whole former
        // feature when a surviving release needs any member (or replaces one of them).
        // Otherwise retaining an old backend can strand it without its policy/provider.
        val survivingIds = installed.keys.filterNot { it in retiringCandidates }.toSet()
        do {
            val targets = builtinAliases.filterKeys { it in required || it in survivingIds }
                .values.flatten().toSet()
            val peers = retiringCandidates.filter { id ->
                id !in required && builtinAliases[id].orEmpty().any(targets::contains)
            }
            peers.forEach { id -> required += id; retainDependencies(id) }
        } while (peers.isNotEmpty())
        builtinAliases.forEach { (previous, replacements) ->
            val priorBuiltin = enabled.remove(previous)
            val lastOffered = loaded.bundledPackages[previous]
            val priorPackage = installed[previous]
            val trackedRelease = lastOffered != null &&
                (priorPackage == null || priorPackage.packageInstallation?.archiveSha256 == lastOffered)
            // A user replacement remains installed; do not activate a conflicting aggregate by default.
            val retained = previous in required
            val oldState = if (retained) false else priorBuiltin ?: if (trackedRelease) priorPackage?.enabled ?: false
                else if (priorPackage != null && replacements.isNotEmpty()) false else null
            if (trackedRelease && !retained) {
                retired += previous
                installed.remove(previous)
            }
            if (oldState != null) {
                require(replacements.all { target -> target in records || bundled.any { it.id == target } }) { "Builtin alias targets unknown plugins" }
                replacements.forEach { target -> inherited[target] = (inherited[target] ?: true) && oldState }
            }
        }
        inherited.forEach { (target, oldState) -> enabled.putIfAbsent(target, oldState) }
        bundledReleaseHistory.putAll(loaded.bundledPackages.filterKeys { it !in retired })
        val offers = bundled.filter { offer ->
            val previous = installed[offer.id]
            val lastOffered = loaded.bundledPackages[offer.id]
            // A missing tracked release is an explicit uninstall. A different release is a user override.
            lastOffered == null && previous == null ||
                lastOffered != null && previous?.packageInstallation?.archiveSha256 == lastOffered
        }
        val replacing = offers.map { it.id }.toSet()
        val requests = offers.map { offer ->
            val previous = installed[offer.id]
            offer.release.copy(
                configuration = previous?.let { StoredPluginConfiguration.encode(it.config) } ?: offer.release.configuration,
                enabled = previous?.enabled ?: enabled[offer.id] ?: offer.release.enabled ?: (offer.id !in disabled),
            )
        }
        val candidates = if (requests.isEmpty()) emptyList() else context.require(KcodePluginPackages.Key).resolver.resolve(
            requests, installed.values.filterNot { it.id in replacing },
        )
        require(candidates.map { it.id }.toSet() == replacing) { "Bundled archive identities do not match the distribution profile" }
        require(candidates.all { candidate ->
            candidate.packageInstallation?.archiveSha256 == offers.single { it.id == candidate.id }.release.sha256
        }) { "Bundled archive hashes do not match their declared identities" }
        candidates.forEach { candidate ->
            installed[candidate.id]?.let { previous ->
                require(previous.version != candidate.version || previous.packageInstallation?.archiveSha256 == candidate.packageInstallation?.archiveSha256) {
                    "A bundled release cannot be republished with different bytes"
                }
            }
        }
        bundled.forEach { offer ->
            bundledReleaseHistory[offer.id] = offer.release.sha256
            enabled.remove(offer.id)
        }
        val snapshot = loaded.copy(
            builtinsEnabled = enabled,
            external = (installed.values.filterNot { it.id in replacing } + candidates).map(StoredDynamicPlugin::from),
            bundledPackages = bundledReleaseHistory.toMap(),
        ).also { it.validate() }
        require(snapshot.builtinsEnabled.keys.all { it in records }) { "Plugin manifest references unknown builtins" }
        restoring = true
        try {
            snapshot.builtinsEnabled.forEach { (id, enabled) -> pluginManager.setEnabled(id, enabled) }
            snapshot.orderedExternal().forEach { stored ->
                val spec = stored.toSpec()
                if (spec.id in records) pluginManager.replace(spec) else pluginManager.install(spec)
            }
            // Publish only after every package mounted successfully. Failed startup leaves the old commit intact.
            if (snapshot != loaded) store.save(compositionSnapshot())
        } finally {
            restoring = false
        }
    }

    private suspend fun detach(record: ManagedPlugin) {
        record.fiber?.dispose()
        record.fiber = null
        record.enabled = false
    }

    private suspend fun restore(id: String, record: ManagedPlugin, enabled: Boolean, error: Throwable) {
        record.enabled = enabled
        if (enabled) {
            try {
                record.fiber = record.mount.mount(context)
                record.fiber?.await()
            } catch (rollback: Throwable) {
                if (rollback !== error) error.addSuppressed(rollback)
            }
        }
        publish(id, record)
    }

    private suspend fun publish(id: String, record: ManagedPlugin) {
        val state = when {
            !record.enabled -> PluginState.Disabled
            record.fiber?.state == FiberState.ACTIVE -> PluginState.Active
            record.fiber?.state == FiberState.FAILED -> PluginState.Failed
            else -> PluginState.Pending
        }
        val descriptor = record.mount.descriptor.copy(state = state)
        if (inventory.snapshot().any { it.id == id }) inventory.replace(descriptor) else inventory.publish(descriptor)
    }

    private suspend fun settle(publishView: Boolean = true) {
        fun fibers() = context.registry.values().flatMap { it.fibers.snapshot() }
        // Providers may create optional consumer children during apply. They belong
        // to the committed tree too, even though they have no top-level inventory ID.
        repeat(records.size + fibers().size + 1) {
            external?.settle()
            val before = fibers().associateWith { it.state }
            before.keys.forEach { it.await() }
            val after = fibers().associateWith { it.state }
            if (before == after && after.keys.none { it.inertia != null }) {
                val externalIds = external?.installed().orEmpty().map { it.id }.toSet()
                records.forEach { (id, record) -> if (id !in externalIds) publish(id, record) }
                if (publishView) publishApplicationView()
                return
            }
        }
        error("plugin tree did not settle")
    }

    private suspend fun publishApplicationView() {
        commitApplicationView(prepareApplicationView())
    }

    private fun commitApplicationView(prepared: PreparedApplicationView) {
        overlayUiSlots.value = prepared.uiSlots
        committedModelCatalog = prepared.modelCatalog
        applicationView.value = prepared.view
    }

    private suspend fun prepareApplicationView(): PreparedApplicationView {
        val uiSlots = context[KcodeUiContributions.Key]?.snapshot() ?: UiContributionsSnapshot()
        context[KcodeConversationCommands.Key]?.commitSnapshot()
        val catalog = context[KcodeLlm.Key]?.catalog() ?: ModelCatalogSnapshot()
        val renderer = context[KcodeApplicationUi.Key]?.renderer
        val view = if (closed || renderer == null) null else {
            val preparing = MutableStateFlow(true)
            val services = object : ApplicationServices {
                override fun <T> get(key: ServiceKey<T>): T? {
                    check(preparing.value) { "Application service lookup is no longer preparing a frame" }
                    return context[key]
                }
            }
            try { renderer.snapshot(services)?.let { ApplicationView(renderer, it) } }
            finally { preparing.value = false }
        }
        return PreparedApplicationView(view, if (closed) UiContributionsSnapshot() else uiSlots, catalog)
    }

    override suspend fun close() {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        val callerContext = currentCoroutineContext()
        check(callerContext[ClosingRuntime]?.runtime !== this) { "runtime cleanup cannot close its own runtime" }
        val currentJob = callerContext[Job]
        val plan = lock.withLock {
            check(currentJob !in turns) { "an active agent turn cannot close its own runtime" }
            if (closed) null else {
                closed = true
                overlayUiSlots.value = UiContributionsSnapshot()
                ClosePlan(
                    turns.keys.toList(),
                    records.values.toList().asReversed().mapNotNull { it.fiber } +
                        bootstrap.asReversed() + context.fiber,
                )
            }
        }
        if (plan == null) {
            withContext(NonCancellable) { closeCompletion.await() }
            return
        }
        withContext(NonCancellable + ClosingRuntime(this)) {
            try {
                plan.turns.forEach { it.cancel() }
                plan.turns.forEach { it.join() }
                var failure: Throwable? = null
                suspend fun dispose(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                    }
                }
                // Admission is closed; no mutation can race these snapshots. Keep the runtime lock
                // free so cleanup callbacks can inspect diagnostics or receive closed-service errors.
                dispose { external?.close() }
                plan.fibers.forEach { fiber -> dispose { fiber.dispose() } }
                dispose { hostInputs?.close() }
                failure?.let { throw it }
                applicationView.value = null
                closeCompletion.complete(Unit)
            } catch (error: Throwable) {
                applicationView.value = null
                closeCompletion.completeExceptionally(error)
                throw error
            }
        }
    }

    companion object {
        suspend fun create(config: KcodePluginRuntimeConfig): KcodePluginRuntime {
            try {
                return createOwned(config)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { config.hostInputs?.close() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                }
                throw error
            }
        }

        private suspend fun createOwned(config: KcodePluginRuntimeConfig): KcodePluginRuntime {
            val overlayUiSlots = MutableStateFlow(UiContributionsSnapshot())
            val overlayHostState = ConversationOverlayHostState(overlayUiSlots.asStateFlow())
            val mounts = linkedMapOf<String, KcodePluginMount>()
            fun add(mount: KcodePluginMount) {
                val id = mount.descriptor.id
                require(id.isNotBlank() && id !in BootstrapIds) { "invalid product plugin id '$id'" }
                require(mounts.put(id, mount) == null) { "duplicate plugin id '$id'" }
            }
            if (config.profile.includeDefaults) (config.bundle ?: nativePluginBundle(NativePluginServices(
                interactionPolicy = config.interactionPolicy,
                settingsBackedInteraction = config.settingsBackedInteraction,
                skillRuntime = config.skillRuntime,
                conversationOverlayFactory = ConversationOverlayFactory {
                    config.conversationOverlayFactory?.invoke(overlayUiSlots)
                },
                settingsStore = config.settingsStore,
                settingsStoreFactory = config.settingsStoreFactory,
                historyRepository = config.historyRepository,
                historyRepositoryFactory = config.historyRepositoryFactory,
                artifactRepository = config.artifactRepository,
                artifactFileStore = config.artifactFileStore,
                artifactFileStoreFactory = config.artifactFileStoreFactory,
                conversationImageSaverFactory = config.conversationImageSaverFactory,
                scheduledTaskNotificationsFactory = config.scheduledTaskNotificationsFactory,
                webContainerController = config.webContainerController,
                pluginCompositionStore = config.pluginCompositionStore,
                conversationOverlayHostState = overlayHostState,
                packagedProviderIds = config.bundledPackages.map { it.id }.toSet(),
            ))).forEach(::add)
            config.featurePlugins.forEach(::add)
            // Persistence is composition infrastructure, independent of the selected product.
            // An explicitly supplied provider remains replaceable through the normal manager.
            if (config.pluginCompositionStore != null && "provider.plugin-installations.platform" !in mounts) {
                add(kcodePlugin(
                    builtinDescriptor("provider.plugin-installations.platform", "pluginInstallations"),
                    PluginInstallationsProviderPlugin,
                    config.pluginCompositionStore,
                ))
            }
            val overrideIds = mutableSetOf<String>()
            config.profile.overrides.forEach { mount ->
                val id = mount.descriptor.id
                require(overrideIds.add(id)) { "duplicate override '$id'" }
                require(id in mounts || config.bundledPackages.any { it.id == id }) { "override refers to unknown plugin '$id'" }
                mounts[id] = mount
            }
            require(config.bundledPackages.map { it.id }.distinct().size == config.bundledPackages.size) { "Duplicate bundled plugin identity" }
            require(config.bundledPackages.all { it.id.isNotBlank() && it.id !in BootstrapIds }) { "Invalid bundled plugin identity" }
            val bundled = config.bundledPackages.filterNot { it.id in overrideIds }
            val bundledIds = bundled.map { it.id }.toSet()
            bundledIds.forEach(mounts::remove)
            require(config.profile.disabled.all { it in mounts || it in bundledIds }) { "profile disables unknown plugin ids" }
            val context = overlayHostState.bind(Context()).let { config.hostInputs?.bind(it) ?: it }
            val bootstrap = mutableListOf<Fiber<*>>()
            val records = linkedMapOf<String, ManagedPlugin>()
            var external: DynamicPluginController? = null
            try {
                bootstrap += context.plugin(PluginInventoryServicePlugin, Unit).await()
                val inventory = context.require(KcodePluginInventory.Key)
                inventory.publish(builtinDescriptor("core.plugin-inventory", "pluginInventory"))
                bootstrap += context.plugin(LoaderPlugin, LoaderConfig()).await()
                inventory.publish(builtinDescriptor("core.loader", "loader"))
                mounts.forEach { (id, mount) ->
                    val record = ManagedPlugin(mount, id !in config.profile.disabled)
                    records[id] = record
                    if (record.enabled) {
                        record.fiber = mount.mount(context)
                        record.fiber?.await()
                    }
                }
                external = config.dynamicPluginControllerFactory?.create(context, context.require(Loader.Key), inventory)
                val aliases = config.builtinAliases
                    ?: if (config.bundle == null && config.profile.includeDefaults) NativeBuiltinAliases else emptyMap()
                return KcodePluginRuntime(context, config.hostInputs, bootstrap, records, inventory, external, aliases, overlayHostState, overlayUiSlots).also {
                    it.restoreInstalledComposition(bundled, config.profile.disabled)
                    it.settle()
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { external?.close() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                    runCatching { context.fiber.dispose() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }
}

private val BootstrapIds = setOf("core.plugin-inventory", "core.loader")

private fun builtinDescriptor(id: String, vararg capabilities: String) =
    PluginDescriptor(id, "builtin", "built-in", capabilities.toSet())


data class KcodePluginDiagnostics(
    val plugins: List<PluginDescriptor>,
    val toolContributions: List<String>,
    val promptSections: List<String>,
    val modelAdapters: List<String>,
)
