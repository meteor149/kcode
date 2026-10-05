package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit
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
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
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
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.plugin.api.UiContributionsSnapshot
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.cordis.Context
import org.cordis.Fiber
import org.cordis.FiberState
import org.cordis.Plugin
import org.cordis.asDynamicPlugin
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin
import org.cordis.loader.EntryOptions
import org.cordis.loader.GroupPlugin
import org.cordis.loader.withTreeTransaction
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.plugin.profiles.profileConfiguration
import ai.meteor.kcode.plugin.profiles.profileLock
import ai.meteor.kcode.plugin.profiles.ProfileStartupFactory
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.profiles.ResolvedProfile
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration

interface DynamicPluginController : AgentPluginManager {
    /** Apply a resolved Profile through the host's managed startup boundary. */
    suspend fun activateProfile(activation: ProfileActivation, builtinModules: List<KcodePluginMount>): Unit =
        error("This controller does not support Profile startup")
    /** Register verified release modules without creating package-level plugin instances. */
    suspend fun registerProfilePackages(specs: List<DynamicPluginSpec>): Map<String, String> =
        error("This controller does not support declarative profile modules")
    suspend fun prepareProfilePackages(specs: List<DynamicPluginSpec>): ProfileModuleTransaction =
        error("This controller does not support Profile module transactions")
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
    val profileActivation: ai.meteor.kcode.plugin.profiles.ProfileActivation? = null,
    val profileStartup: ProfileStartupFactory? = null,
    val profileBuiltinModules: List<KcodePluginMount> = emptyList(),
    val profileModuleFactories: Map<String, () -> KcodePluginMount> = emptyMap(),
    val settingsStore: AppSettingsStore? = null,
    val settingsStoreFactory: SettingsStoreFactory? = null,
    val historyRepositoryFactory: HistoryRepositoryFactory? = null,
    val historyRepository: ConversationHistoryRepository? = null,
    val bundle: List<KcodePluginMount>? = null,
    val pluginCompositionStore: PluginCompositionStore? = null,
    val builtinAliases: Map<String, Set<String>>? = null,
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
    private var activeProfile: ProfileActivation? = null
    private var profileStartupOpen = false
    private val profileDescriptors = linkedMapOf<String, PluginDescriptor>()
    private val profileModules = linkedMapOf<String, KcodePluginMount>()
    private data class ProfileBinding(val key: String, val packageId: String, val configuration: StoredPluginConfiguration?, val source: Any?, val export: Any?)
    private val profileBindings = linkedMapOf<String, ProfileBinding>()
    private var profileBindingSequence = 0L

    private fun bindProfileTree(
        resolved: ResolvedProfile,
        exports: Map<String, Any?>,
        bindings: MutableMap<String, ProfileBinding>,
        descriptors: MutableMap<String, PluginDescriptor>,
        modules: Map<String, KcodePluginMount> = profileModules,
    ): List<EntryOptions> {
        val loader = context.require(Loader.Key)
        val installed = resolved.packages.associateBy { it.id }
        fun bind(items: List<EntryOptions>): List<EntryOptions> = items.map { entry ->
            require(entry.id !in BootstrapIds && entry.id !in records) { "Profile entry '${entry.id}' conflicts with infrastructure" }
            if (entry.group == true) {
                require(entry.name == "core.group" || entry.name == "cordis:group") { "Profile groups use core.group" }
                entry.copy(name = "cordis:group", config = bind((entry.config as List<*>).map { it as EntryOptions }))
            } else {
                val configured = profileConfiguration(entry)
                val spec = installed[entry.name]
                val builtin = if (spec == null) modules[entry.name] else null
                require(spec != null || builtin != null) { "Unknown Profile module '${entry.name}'" }
                val source = if (spec != null) exports.getValue(spec.id) else builtin
                val previous = bindings[entry.id]
                val binding = if (previous?.packageId == entry.name && previous.configuration == configured && previous.source === source) previous
                    else ProfileBinding("profile-binding-${++profileBindingSequence}", entry.name, configured, source,
                        if (builtin != null) builtin.export(configured) else source)
                bindings[entry.id] = binding
                loader.builtins[binding.key] = binding.export
                descriptors[entry.id] = if (builtin != null) builtin.descriptor.copy(id = entry.id)
                    else spec!!.let { PluginDescriptor(entry.id, it.version, it.artifactPath, it.capabilities) }
                entry.copy(name = "cordis:${binding.key}", config = if (builtin != null) Unit else if (configured != null) configured.decode() else spec!!.config,
                    extra = entry.extra + ("kcode.packageId" to entry.name))
            }
        }
        return bind(resolved.composition.entries)
    }

    private suspend fun validateProfileConfigurations(items: List<EntryOptions>) {
        val loader = context.require(Loader.Key)
        items.forEach { entry ->
            val plugin = requireNotNull(loader.unwrapExports(loader.import(entry.name)).asDynamicPlugin()) {
                "Profile module '${entry.name}' does not export a plugin"
            }
            plugin.config?.validate(entry.config)
            if (entry.group == true) validateProfileConfigurations((entry.config as List<*>).map { it as EntryOptions })
        }
    }

    private suspend fun activateProfile(activation: ProfileActivation, builtinModules: List<KcodePluginMount>) = lock.withLock {
        check(profileStartupOpen && !closed && activeProfile == null && turns.isEmpty()) { "Profile startup requires a fresh declarative runtime" }
        profileStartupOpen = false
        val resolved = activation.resolved
        resolved.definition.validate()
        resolved.composition.requireValid()
        require(resolved.lock == profileLock(PluginCompositionSnapshot(external = resolved.packages.map(StoredDynamicPlugin::from)))) {
            "Resolved Profile lock does not match its deployment packages"
        }
        require(builtinModules.map { it.descriptor.id }.distinct().size == builtinModules.size) { "Duplicate profile builtin module" }
        profileModules.putAll(builtinModules.associateBy { it.descriptor.id })
        resolved.packages.forEach { validateInstallation(it) }
        val loader = context.require(Loader.Key)
        val urls = if (resolved.packages.isEmpty()) emptyMap() else dynamic().registerProfilePackages(resolved.packages)
        loader.builtins["group"] = GroupPlugin
        val descriptors = linkedMapOf<String, PluginDescriptor>()
        val exports = urls.mapValues { (_, url) -> loader.import(url) }
        val tree = bindProfileTree(resolved, exports, profileBindings, descriptors)
        validateProfileConfigurations(tree)
        require(descriptors.keys.none { id -> resolved.packages.any { "package:${it.id}" == id } }) {
            "Profile instance conflicts with a package inventory identity"
        }
        // Inventory validation and settling precede durable publication.
        resolved.packages.forEach { spec ->
            inventory.publish(PluginDescriptor("package:${spec.id}", spec.version, spec.artifactPath, spec.capabilities))
        }
        descriptors.values.forEach { inventory.publish(it) }
        profileDescriptors.putAll(descriptors)
        loader.withTreeTransaction(tree) {
            settle(publishView = false)
            val prepared = prepareApplicationView()
            val snapshot = compositionSnapshot().copy(
                external = resolved.packages.map(StoredDynamicPlugin::from),
                builtinsEnabled = records.mapValues { it.value.enabled } + descriptors.mapValues { (id, _) -> !loader.resolve(id).disabled },
            )
            activation.session.commitDefinition(resolved.definition, snapshot, resolved.bundles)
            // Everything after the atomic publisher is in-memory and cannot allocate providers.
            activeProfile = activation
            commitApplicationView(prepared)
        }
    }
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
        override suspend fun currentProfile(): ProfileCompositionState? {
            PluginOperationOwner.requireOutsideCall()
            return lock.withLock {
                check(!closed) { "plugin runtime is closed" }
                activeProfile?.session?.currentCompositionState()
            }
        }

        override suspend fun editProfile(edit: ProfileCompositionEdit): ProfileCompositionState {
            PluginOperationOwner.requireOutsideCall()
            ChatGenerationRunner.requireOutsideCall()
            val serializer = ListSerializer(ProfileOperation.serializer())
            val operations = Json.decodeFromString(serializer, Json.encodeToString(serializer, edit.operations))
            return lock.withLock {
                check(!closed) { "plugin runtime is closed" }
                check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
                val activation = checkNotNull(activeProfile) { "This runtime does not use Profiles" }
                val current = activation.session.currentCompositionState()
                require(edit.profileId == current.definition.id && edit.expectedGeneration == current.generation) { "Profile edit is stale" }
                if (operations.isNotEmpty()) {
                    val definition = current.definition.copy(patches = current.definition.patches + operations)
                    val candidate = resolveProfileCandidate(activation, definition, activation.resolved.packages.associateBy { it.id })
                    applyProfileChange(activation, candidate)
                }
                activation.session.currentCompositionState()
            }
        }

        override suspend fun activateProfile(activation: ProfileActivation, builtinModules: List<KcodePluginMount>) {
            PluginOperationOwner.requireOutsideCall()
            ChatGenerationRunner.requireOutsideCall()
            this@KcodePluginRuntime.activateProfile(activation, builtinModules)
        }
        override suspend fun importPackages(packages: List<PluginPackageImport>) {
            if (tryChangeProfile(PluginCompositionChange(), imports = packages)) return
            var specs = emptyList<DynamicPluginSpec>()
            mutate(prepare = {
                require(packages.isNotEmpty()) { "No plugin packages supplied" }
                specs = context.require(KcodePluginPackages.Key).resolver.resolve(packages, dynamic().installed())
            }) {
                applyCompositionChange(PluginCompositionChange(upserts = specs))
            }
        }

        override suspend fun applyChanges(changes: PluginCompositionChange) {
            if (changes.upserts.isEmpty() && changes.removals.isEmpty() && trySetProfileEnabled(changes.enabled)) return
            if (tryChangeProfile(changes)) return
            mutate { applyCompositionChange(changes) }
        }

        override suspend fun install(spec: DynamicPluginSpec) {
            if (tryChangeProfile(PluginCompositionChange(upserts = listOf(spec)), mode = "install")) return
            installLegacy(spec)
        }
        private suspend fun installLegacy(spec: DynamicPluginSpec) = mutate {
            validateInstallation(spec)
            require(spec.id !in records) { "plugin '${spec.id}' is configured; use replace()" }
            validatePackageDependencies(dynamic().installed() + spec)
            dynamic().install(spec)
        }

        override suspend fun replace(spec: DynamicPluginSpec) {
            if (tryChangeProfile(PluginCompositionChange(upserts = listOf(spec)), mode = "replace")) return
            replaceLegacy(spec)
        }
        private suspend fun replaceLegacy(spec: DynamicPluginSpec) = mutate {
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

        override suspend fun uninstall(id: String) {
            if (tryChangeProfile(PluginCompositionChange(removals = setOf(id)))) return
            uninstallLegacy(id)
        }
        private suspend fun uninstallLegacy(id: String) = mutate {
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

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            if (trySetProfileEnabled(mapOf(id to enabled))) return
            setLegacyEnabled(id, enabled)
        }

        private suspend fun setLegacyEnabled(id: String, enabled: Boolean) = mutate {
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

    private suspend fun tryChangeProfile(
        changes: PluginCompositionChange,
        mode: String? = null,
        imports: List<PluginPackageImport>? = null,
    ): Boolean {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        return lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            val activation = activeProfile ?: return@withLock false
            check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
            val previous = activation.resolved
            val imported = imports?.let {
                require(it.isNotEmpty()) { "No plugin packages supplied" }
                context.require(KcodePluginPackages.Key).resolver.resolve(it, previous.packages)
            }
            val upserts = imported ?: changes.upserts
            require(upserts.map { it.id }.distinct().size == upserts.size) { "Duplicate composition upsert" }
            require(upserts.none { it.id in changes.removals } && changes.enabled.keys.none { it in changes.removals }) { "Conflicting composition changes" }
            val entries = linkedMapOf<String, EntryOptions>()
            fun index(items: List<EntryOptions>) {
                items.forEach { entry ->
                    entries[entry.id] = entry
                    if (entry.group == true) index((entry.config as List<*>).map { it as EntryOptions })
                }
            }
            index(previous.composition.entries)
            val available = previous.packages.associateBy { it.id }.toMutableMap()
            val operations = mutableListOf<ProfileOperation>()
            changes.removals.forEach { id ->
                val targets = if (id in entries) listOf(id) else entries.values.filter { it.name == id }.map { it.id }
                require(targets.isNotEmpty()) { "Unknown Profile instance '$id'" }
                targets.forEach { operations += ProfileOperation.Remove(it) }
            }
            upserts.forEach { spec ->
                validateInstallation(spec)
                val old = available[spec.id]
                val oldRelease = old?.packageInstallation
                val newRelease = spec.packageInstallation
                if (old?.version == spec.version && oldRelease != null && newRelease != null) {
                    require(oldRelease.archiveSha256 == newRelease.archiveSha256) { "A package release cannot be republished with different bytes" }
                }
                if (mode == "install") require(old == null && spec.id !in entries) { "Plugin '${spec.id}' exists; use replace" }
                if (mode == "replace") require(old != null || spec.id in entries) { "Plugin '${spec.id}' is not configured" }
                available[spec.id] = spec.copy(enabled = true)
                val requestedImport = imports?.firstOrNull { it.sha256.equals(spec.packageInstallation?.archiveSha256, ignoreCase = true) }
                val requested = imports == null || requestedImport != null
                if (requested) {
                    val configured = StoredPluginConfiguration.encode(spec.config)
                    val entry = entries[spec.id]
                    if (entry == null) {
                        // Upgrading available code must not resurrect a deliberately removed
                        // default instance while independently named instances retain the release.
                        if (old == null) operations += ProfileOperation.Insert(listOf(ProfileEntry(spec.id, spec.id,
                            configured.value, enabled = spec.enabled, configurationKind = configured.kind)))
                    }
                    else {
                        require(entry.group != true) { "Cannot replace a structural Group with a release" }
                        if (entry.name != spec.id) operations += ProfileOperation.Replace(entry.id, spec.id, entry.name)
                        if (imports == null || requestedImport?.configuration != null) {
                            operations += ProfileOperation.Configure(entry.id, configured.value, configured.kind)
                        }
                        val enabled = if (imports == null) spec.enabled else requestedImport?.enabled
                        if (enabled != null) operations += if (enabled) ProfileOperation.Enable(entry.id) else ProfileOperation.Disable(entry.id)
                    }
                }
            }
            changes.enabled.forEach { (id, enabled) -> operations += if (enabled) ProfileOperation.Enable(id) else ProfileOperation.Disable(id) }
            val definition = previous.definition.copy(patches = previous.definition.patches + operations)
            val candidate = resolveProfileCandidate(activation, definition, available)
            applyProfileChange(activation, candidate)
            true
        }
    }

    private suspend fun resolveProfileCandidate(
        activation: ProfileActivation,
        definition: ProfileDefinition,
        available: Map<String, DynamicPluginSpec>,
        modules: Map<String, KcodePluginMount> = profileModules,
    ): ResolvedProfile {
        val previous = activation.resolved
        val machine = activation.machineConfiguration?.invoke(definition, previous.bundles) ?: previous.machineOverrides
        val composition = ProfileCompiler().compile(definition, previous.bundles, machine, previous.launchOverrides).requireValid()
        val required = linkedSetOf<String>()
        fun retain(id: String) {
            if (!required.add(id)) return
            val spec = requireNotNull(available[id]) { "Missing Profile package '$id'" }
            (spec.dependencies + spec.packageInstallation?.dependencies.orEmpty().keys).forEach(::retain)
        }
        fun visit(items: List<EntryOptions>) {
            items.forEach { entry ->
                if (entry.group == true) visit((entry.config as List<*>).map { it as EntryOptions })
                else if (entry.name in available) retain(entry.name)
                else require(entry.name in modules) { "Unknown Profile module '${entry.name}'" }
            }
        }
        visit(composition.entries)
        val snapshot = PluginCompositionSnapshot(external = required.map { StoredDynamicPlugin.from(available.getValue(it)) }).also { it.validate() }
        val packages = snapshot.orderedExternal().map { available.getValue(it.id) }
        packages.forEach { validateInstallation(it) }
        return previous.copy(definition = definition, composition = composition, packages = packages,
            lock = profileLock(snapshot), machineOverrides = machine)
    }

    private suspend fun applyProfileChange(
        activation: ProfileActivation,
        candidate: ResolvedProfile,
        modules: Map<String, KcodePluginMount> = profileModules,
    ) {
        val loader = context.require(Loader.Key)
        val beforeInventory = inventory.snapshot()
        val beforeDescriptors = profileDescriptors.toMap()
        val oldKeys = profileBindings.values.mapTo(mutableSetOf()) { it.key }
        val bindings = profileBindings.toMutableMap()
        val descriptors = linkedMapOf<String, PluginDescriptor>()
        val oldPackageIds = activation.resolved.packages.mapTo(mutableSetOf()) { "package:${it.id}" }
        require(candidate.packages.none { spec -> beforeInventory.any { it.id == "package:${spec.id}" && it.id !in oldPackageIds } }) {
            "Profile package conflicts with an infrastructure inventory identity"
        }
        val moduleTransaction = if (candidate.packages.isNotEmpty() || activation.resolved.packages.isNotEmpty())
            dynamic().prepareProfilePackages(candidate.packages) else null
        try {
            val tree = bindProfileTree(candidate, moduleTransaction?.exports.orEmpty(), bindings, descriptors, modules)
            bindings.keys.retainAll(descriptors.keys)
            validateProfileConfigurations(tree)
            require(descriptors.keys.none { id -> candidate.packages.any { "package:${it.id}" == id } }) { "Profile instance conflicts with package inventory" }
            val removedIds = beforeDescriptors.keys - descriptors.keys
            val packageIds = candidate.packages.mapTo(mutableSetOf()) { "package:${it.id}" }
            (removedIds + (oldPackageIds - packageIds)).forEach { inventory.remove(it) }
            candidate.packages.forEach { spec ->
                val descriptor = PluginDescriptor("package:${spec.id}", spec.version, spec.artifactPath, spec.capabilities)
                if (beforeInventory.any { it.id == descriptor.id }) inventory.replace(descriptor) else inventory.publish(descriptor)
            }
            descriptors.values.forEach { descriptor ->
                if (beforeInventory.any { it.id == descriptor.id }) inventory.replace(descriptor) else inventory.publish(descriptor)
            }
            profileDescriptors.clear()
            profileDescriptors.putAll(descriptors)
            loader.withTreeTransaction(tree) {
                settle(publishView = false)
                val prepared = prepareApplicationView()
                val snapshot = compositionSnapshot().copy(external = candidate.packages.map(StoredDynamicPlugin::from),
                    builtinsEnabled = records.mapValues { it.value.enabled } + descriptors.mapValues { (id, _) -> !loader.resolve(id).disabled })
                activation.session.commitDefinition(candidate.definition, snapshot, candidate.bundles)
                moduleTransaction?.commit()
                activeProfile = activation.copy(resolved = candidate)
                profileModules.putAll(modules)
                profileBindings.clear()
                profileBindings.putAll(bindings)
                val keep = bindings.values.mapTo(mutableSetOf()) { it.key }
                (oldKeys - keep).forEach(loader.builtins::remove)
                commitApplicationView(prepared)
            }
        } catch (error: Throwable) {
            // Cordis restores old bindings before candidate code resources can be released.
            withContext(NonCancellable) {
                runCatching { moduleTransaction?.rollback() }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                profileDescriptors.clear()
                profileDescriptors.putAll(beforeDescriptors)
                val owned = (beforeDescriptors.keys + descriptors.keys + activation.resolved.packages.map { "package:${it.id}" } + candidate.packages.map { "package:${it.id}" }).toSet()
                owned.forEach { inventory.remove(it) }
                beforeInventory.filter { it.id in owned }.forEach { inventory.publish(it) }
                bindings.values.map { it.key }.filter { it !in oldKeys }.forEach(loader.builtins::remove)
                runCatching { settle(publishView = true) }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
            }
            throw error
        }
    }

    /** Entry enable state is portable intent; release availability and instance identity stay fixed. */
    private suspend fun trySetProfileEnabled(changes: Map<String, Boolean>): Boolean {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        return lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            val activation = activeProfile ?: return@withLock false
            check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
            if (changes.isEmpty()) return@withLock true
            val previous = activation.resolved
            val definition = previous.definition.copy(patches = previous.definition.patches + changes.map { (id, enabled) ->
                if (enabled) ProfileOperation.Enable(id) else ProfileOperation.Disable(id)
            })
            val composition = ProfileCompiler().compile(definition, previous.bundles,
                previous.machineOverrides, previous.launchOverrides).requireValid()
            val flags = linkedMapOf<String, Boolean?>()
            fun index(items: List<EntryOptions>) {
                items.forEach { entry ->
                    flags[entry.id] = entry.disabled
                    if (entry.group == true) index((entry.config as List<*>).map { it as EntryOptions })
                }
            }
            index(composition.entries)
            val loader = context.require(Loader.Key)
            // Keep the exact configured module exports and per-instance code origins. Only
            // enable flags change; machine/launch layers still outrank user intent.
            fun bind(items: List<EntryOptions>): List<EntryOptions> = items.map { entry ->
                require(entry.id in flags) { "Profile runtime tree differs from committed intent" }
                entry.copy(disabled = flags.getValue(entry.id), config = if (entry.group == true)
                    bind((entry.config as List<*>).map { it as EntryOptions }) else entry.config)
            }
            val tree = bind(loader.root.data)
            try {
                loader.withTreeTransaction(tree) {
                    settle(publishView = false)
                    val prepared = prepareApplicationView()
                    val snapshot = compositionSnapshot().copy(
                        external = previous.packages.map(StoredDynamicPlugin::from),
                        builtinsEnabled = records.mapValues { it.value.enabled } + profileDescriptors.mapValues { (id, _) -> !loader.resolve(id).disabled },
                    )
                    activation.session.commitDefinition(definition, snapshot, previous.bundles)
                    activeProfile = activation.copy(resolved = previous.copy(definition = definition, composition = composition))
                    commitApplicationView(prepared)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { settle(publishView = true) }.exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                }
                throw error
            }
            true
        }
    }

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

    /** Legacy mounts replace in place; Profiles persist an explicit alternate module identity. */
    suspend fun replacePlugin(replacement: KcodePluginMount) = replacePlugin(replacement.descriptor.id, replacement)

    /** Select alternate typed code for every instance of a Profile module, retaining instance intent. */
    suspend fun replacePlugin(packageId: String, replacement: KcodePluginMount) {
        if (tryReplaceProfileModule(packageId, replacement)) return
        require(packageId == replacement.descriptor.id) { "Legacy replacement must retain the configured plugin ID" }
        replaceLegacyPlugin(replacement)
    }

    suspend fun selectProfileModule(
        packageId: String,
        moduleId: String,
        expected: ProfileCompositionState,
    ): ProfileCompositionState {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        return lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            val activation = checkNotNull(activeProfile) { "Runtime has no active Profile" }
            check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
            val current = activation.session.currentCompositionState()
            require(expected.definition.id == current.definition.id && expected.generation == current.generation) {
                "Active Profile changed; refresh before selecting a module"
            }
            val replacement = requireNotNull(profileModules[moduleId]) { "Unknown Profile module '$moduleId'" }
            replaceProfileModuleLocked(activation, packageId, replacement)
            activation.session.currentCompositionState()
        }
    }

    private suspend fun tryReplaceProfileModule(packageId: String, replacement: KcodePluginMount): Boolean {
        PluginOperationOwner.requireOutsideCall()
        ChatGenerationRunner.requireOutsideCall()
        return lock.withLock {
            check(!closed) { "plugin runtime is closed" }
            val activation = activeProfile ?: return@withLock false
            check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
            replaceProfileModuleLocked(activation, packageId, replacement)
            true
        }
    }

    private suspend fun replaceProfileModuleLocked(activation: ProfileActivation, packageId: String, replacement: KcodePluginMount) {
        require(packageId in profileModules && activation.resolved.packages.none { it.id == packageId }) {
            "Profile module '$packageId' is not an in-process module"
        }
        val replacementId = replacement.descriptor.id
        require(replacementId.isNotBlank() && replacementId !in BootstrapIds && replacementId !in records) {
            "Invalid replacement module identity"
        }
        require(replacementId != packageId) {
            "Profile typed replacement requires a distinct stable module ID; use a verified package for same-ID release upgrades"
        }
        require(activation.resolved.packages.none { it.id == replacementId }) { "Replacement identity belongs to an external release" }
        val existing = profileModules[replacementId]
        require(existing == null || existing === replacement) { "Replacement module ID already identifies different code" }
        val operations = mutableListOf<ProfileOperation>()
        fun visit(entries: List<EntryOptions>) {
            entries.forEach { entry ->
                if (entry.group == true) visit((entry.config as List<*>).map { it as EntryOptions })
                else if (entry.name == packageId) operations += ProfileOperation.Replace(entry.id, replacementId, packageId)
            }
        }
        visit(activation.resolved.composition.entries)
        require(operations.isNotEmpty()) { "Profile module '$packageId' has no configured instances" }
        val modules = profileModules.toMap() + (replacementId to replacement)
        val candidate = resolveProfileCandidate(activation,
            activation.resolved.definition.copy(patches = activation.resolved.definition.patches + operations),
            activation.resolved.packages.associateBy { it.id }, modules)
        applyProfileChange(activation, candidate, modules)
    }

    /** Typed in-process legacy replacement. Restore the previous implementation if apply fails. */
    private suspend fun replaceLegacyPlugin(replacement: KcodePluginMount) = mutate {
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
            check(activeProfile == null) { "Declarative Profile changes require a Profile transaction" }
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
                profileDescriptors.forEach { (id, descriptor) ->
                    val entry = context.require(Loader.Key).resolve(id)
                    inventory.replace(descriptor.copy(state = when {
                        entry.disabled -> PluginState.Disabled
                        entry.fiber?.state == FiberState.ACTIVE -> PluginState.Active
                        entry.fiber?.state == FiberState.FAILED -> PluginState.Failed
                        else -> PluginState.Pending
                    }))
                }
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
        val commands = context[KcodeConversationCommands.Key]?.commitSnapshot() ?: ConversationCommandSnapshot()
        val catalog = context[KcodeLlm.Key]?.catalog() ?: ModelCatalogSnapshot()
        val renderer = context[KcodeApplicationUi.Key]?.renderer
        val view = if (closed || renderer == null) null else {
            val preparing = MutableStateFlow(true)
            val services = object : ApplicationServices {
                override val uiContributions: UiContributionsSnapshot
                    get() { check(preparing.value); return uiSlots }
                override val modelCatalog: ModelCatalogSnapshot
                    get() { check(preparing.value); return catalog }
                override val conversationCommands: ConversationCommandSnapshot
                    get() { check(preparing.value); return commands }

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
            require(config.profile.overrides.map { it.descriptor.id }.distinct().size == config.profile.overrides.size) { "Duplicate Profile override" }
            require(config.profileBuiltinModules.map { it.descriptor.id }.distinct().size == config.profileBuiltinModules.size) { "Duplicate Profile builtin module" }
            require(config.profileActivation == null || (config.profile.disabled.isEmpty() && config.profile.overrides.isEmpty())) {
                "Legacy Profile overrides cannot be combined with a declarative Profile"
            }
            require(config.profileActivation == null || config.profileStartup == null) { "Choose one Profile startup source" }
            val overlayUiSlots = MutableStateFlow(UiContributionsSnapshot())
            val overlayHostState = ConversationOverlayHostState(overlayUiSlots.asStateFlow())
            val mounts = linkedMapOf<String, KcodePluginMount>()
            fun add(mount: KcodePluginMount) {
                val id = mount.descriptor.id
                require(id.isNotBlank() && id !in BootstrapIds) { "invalid product plugin id '$id'" }
                require(mounts.put(id, mount) == null) { "duplicate plugin id '$id'" }
            }
            val defaults = if (config.profileActivation == null && config.profile.includeDefaults) (config.bundle ?: nativePluginBundle(NativePluginServices(
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
                conversationImageSaverFactory = config.conversationImageSaverFactory,
                scheduledTaskNotificationsFactory = config.scheduledTaskNotificationsFactory,
                pluginCompositionStore = config.pluginCompositionStore,
                conversationOverlayHostState = overlayHostState,
                packagedProviderIds = config.bundledPackages.map { it.id }.toSet(),
            ))) else emptyList()
            val profileModules = linkedMapOf<String, KcodePluginMount>()
            if (config.profileStartup != null || config.profileActivation != null) {
                defaults.filterNot { it.descriptor.id == "provider.plugin-installations.platform" }.forEach { module ->
                    profileModules[module.descriptor.id] = module
                }
                config.profileBuiltinModules.forEach { module ->
                    require(module.descriptor.id.isNotBlank() && module.descriptor.id !in BootstrapIds)
                    profileModules[module.descriptor.id] = module
                }
                config.profile.overrides.forEach { module ->
                    require(module.descriptor.id in profileModules || config.bundledPackages.any { it.id == module.descriptor.id }) {
                        "Override refers to unknown Profile module '${module.descriptor.id}'"
                    }
                    profileModules[module.descriptor.id] = module
                }
                config.profileModuleFactories.forEach { (id, factory) ->
                    require(id.isNotBlank() && id !in BootstrapIds && id != "provider.plugin-installations.platform" &&
                        id !in profileModules && config.featurePlugins.none { it.descriptor.id == id } &&
                        config.bundledPackages.none { it.id == id }) { "Alternate module '$id' conflicts with the native catalogue" }
                    val module = factory()
                    require(module.descriptor.id == id) { "Alternate module factory identity mismatch for '$id'" }
                    profileModules[id] = module
                }
            } else {
                require(config.profileModuleFactories.isEmpty()) { "Alternate modules require declarative Profile startup" }
                defaults.forEach(::add)
            }
            val activation = config.profileActivation ?: config.profileStartup?.prepare(profileModules.values.toList())
            val compositionStore = activation?.session ?: config.pluginCompositionStore
            config.featurePlugins.forEach(::add)
            // Persistence is composition infrastructure, independent of the selected product.
            // An explicitly supplied provider remains replaceable through the normal manager.
            if (compositionStore != null && "provider.plugin-installations.platform" !in mounts) {
                add(kcodePlugin(
                    builtinDescriptor("provider.plugin-installations.platform", "pluginInstallations"),
                    PluginInstallationsProviderPlugin,
                    compositionStore,
                ))
            }
            val overrideIds = mutableSetOf<String>()
            if (activation == null) config.profile.overrides.forEach { mount ->
                val id = mount.descriptor.id
                require(overrideIds.add(id)) { "duplicate override '$id'" }
                require(id in mounts || config.bundledPackages.any { it.id == id }) { "override refers to unknown plugin '$id'" }
                mounts[id] = mount
            }
            require(config.bundledPackages.map { it.id }.distinct().size == config.bundledPackages.size) { "Duplicate bundled plugin identity" }
            require(config.bundledPackages.all { it.id.isNotBlank() && it.id !in BootstrapIds }) { "Invalid bundled plugin identity" }
            val bundled = if (activation != null) emptyList() else config.bundledPackages.filterNot { it.id in overrideIds }
            val bundledIds = bundled.map { it.id }.toSet()
            bundledIds.forEach(mounts::remove)
            if (activation == null) require(config.profile.disabled.all { it in mounts || it in bundledIds }) { "profile disables unknown plugin ids" }
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
                    if (activation != null) {
                        it.profileStartupOpen = true
                        it.pluginManager.activateProfile(activation,
                            profileModules.values.toList())
                    } else {
                        it.restoreInstalledComposition(bundled, config.profile.disabled)
                        it.settle()
                    }
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
