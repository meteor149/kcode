package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentRuntimeOwner
import ai.meteor.kcode.ApplicationContent
import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.EmptyArtifactRepository
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.TransientConversationHistoryRepository
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationViewServices
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodePluginInventory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeSystemPrompt
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.TransientAppSettingsStore
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Fiber
import org.cordis.FiberState
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin
import org.cordis.Plugin

interface DynamicPluginController : AgentPluginManager {
    suspend fun close()
    suspend fun settle()
}

fun interface DynamicPluginControllerFactory {
    fun create(context: Context, loader: Loader, inventory: KcodePluginInventory): DynamicPluginController
}

interface KcodePluginMount {
    val descriptor: PluginDescriptor
    suspend fun mount(context: Context): Fiber<*>
}

fun <C> kcodePlugin(descriptor: PluginDescriptor, plugin: Plugin<C>, config: C): KcodePluginMount =
    object : KcodePluginMount {
        override val descriptor = descriptor
        override suspend fun mount(context: Context): Fiber<*> = context.plugin(plugin, config)
    }

/** A declarative patch over the default bundle; ids are checked before mounting. */
data class KcodePluginProfile(
    val includeDefaults: Boolean = true,
    val disabled: Set<String> = emptySet(),
    val overrides: List<KcodePluginMount> = emptyList(),
)

data class KcodePluginRuntimeConfig(
    val interactionPolicy: InteractionPolicy,
    val skillRuntime: SkillRuntime? = null,
    val conversationOverlayController: ai.meteor.kcode.AgentConversationOverlayController? = null,
    val featurePlugins: List<KcodePluginMount> = emptyList(),
    val dynamicPluginControllerFactory: DynamicPluginControllerFactory? = null,
    val profile: KcodePluginProfile = KcodePluginProfile(),
    val settingsStore: AppSettingsStore = TransientAppSettingsStore,
    val historyRepository: ConversationHistoryRepository = TransientConversationHistoryRepository,
    val artifactRepository: ArtifactRepository = EmptyArtifactRepository,
    val webContainerController: WebContainerController? = null,
)

/** Only inventory and the loader bridge are bootstrap infrastructure. Product providers are managed. */
class KcodePluginRuntime private constructor(
    private val context: Context,
    private val bootstrap: List<Fiber<*>>,
    private val records: LinkedHashMap<String, ManagedPlugin>,
    val inventory: KcodePluginInventory,
    private val external: DynamicPluginController?,
) : AgentRuntimeOwner, ApplicationContent {
    private class ManagedPlugin(
        var mount: KcodePluginMount,
        var enabled: Boolean,
        var fiber: Fiber<*>? = null,
    )

    private data class ApplicationView(
        val renderer: ApplicationRenderer,
        val services: ApplicationViewServices,
    )

    private val lock = Mutex()
    private val turns = mutableMapOf<Job, Int>()
    private var closed = false
    private val applicationView = MutableStateFlow<ApplicationView?>(null)

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
        override suspend fun install(spec: DynamicPluginSpec) = mutate {
            require(spec.id !in records) { "plugin '${spec.id}' is configured; use replace()" }
            dynamic().install(spec)
        }

        override suspend fun replace(spec: DynamicPluginSpec) = mutate {
            val record = records[spec.id]
            if (record == null || dynamic().installed().any { it.id == spec.id }) {
                dynamic().replace(spec)
            } else {
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
            if (external?.installed()?.any { it.id == id } == true) {
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
                            runCatching { detach(record) }.exceptionOrNull()?.let(error::addSuppressed)
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
        override suspend fun settle() = lock.withLock { this@KcodePluginRuntime.settle() }
    }

    val dynamicPlugins: DynamicPluginController? get() = if (external == null) null else pluginManager

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
            runCatching { detach(record) }.exceptionOrNull()?.let(error::addSuppressed)
            record.mount = previous
            restore(id, record, wasEnabled, error)
            throw error
        }
    }

    suspend fun diagnostics(): KcodePluginDiagnostics = lock.withLock {
        settle()
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
        val services = active.services
        key(active.renderer, services.settingsStore, services.historyRepository, services.artifactRepository, services.webContainerController) {
            active.renderer.Render(services, options)
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

    private suspend fun mutate(block: suspend () -> Unit) = lock.withLock {
        check(!closed) { "plugin runtime is closed" }
        check(turns.isEmpty()) { "finish or cancel active agent turns before changing plugins" }
        withContext(NonCancellable) {
            try {
                block()
            } finally {
                settle()
            }
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
                error.addSuppressed(rollback)
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

    private suspend fun settle() {
        repeat(records.size + 1) {
            val before = records.values.map { it.fiber?.state }
            external?.settle()
            records.values.forEach { it.fiber?.await() }
            if (before == records.values.map { it.fiber?.state }) {
                val externalIds = external?.installed().orEmpty().map { it.id }.toSet()
                records.forEach { (id, record) -> if (id !in externalIds) publish(id, record) }
                publishApplicationView()
                return
            }
        }
        error("plugin tree did not settle")
    }

    private fun publishApplicationView() {
        val renderer = context[KcodeApplicationUi.Key]?.renderer
        val settings = context[KcodeSettings.Key]?.store
        val history = context[KcodeHistory.Key]?.repository
        val artifacts = context[KcodeArtifacts.Key]?.repository
        val webContainers = context[KcodeWebContainers.Key]
        applicationView.value = if (closed || renderer == null || settings == null || history == null || artifacts == null || webContainers == null) {
            null
        } else {
            ApplicationView(renderer, ApplicationViewServices(chatService, settings, history, artifacts, webContainers.controller))
        }
    }

    override suspend fun close() {
        val currentJob = currentCoroutineContext()[Job]
        val jobs = lock.withLock {
            if (closed) return
            check(currentJob !in turns) { "an active agent turn cannot close its own runtime" }
            closed = true
            turns.keys.toList()
        }
        withContext(NonCancellable) {
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            lock.withLock {
                var failure: Throwable? = null
                suspend fun dispose(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else failure.addSuppressed(error)
                    }
                }
                dispose { external?.close() }
                records.values.toList().asReversed().forEach { record -> dispose { record.fiber?.dispose() } }
                bootstrap.asReversed().forEach { fiber -> dispose { fiber.dispose() } }
                dispose { context.fiber.dispose() }
                applicationView.value = null
                failure?.let { throw it }
            }
        }
    }

    companion object {
        suspend fun create(config: KcodePluginRuntimeConfig): KcodePluginRuntime {
            val mounts = linkedMapOf<String, KcodePluginMount>()
            fun add(mount: KcodePluginMount) {
                val id = mount.descriptor.id
                require(id.isNotBlank() && id !in BootstrapIds) { "invalid product plugin id '$id'" }
                require(mounts.put(id, mount) == null) { "duplicate plugin id '$id'" }
            }
            if (config.profile.includeDefaults) defaultPlugins(config).forEach(::add)
            config.featurePlugins.forEach(::add)
            val overrideIds = mutableSetOf<String>()
            config.profile.overrides.forEach { mount ->
                val id = mount.descriptor.id
                require(overrideIds.add(id)) { "duplicate override '$id'" }
                require(id in mounts) { "override refers to unknown plugin '$id'" }
                mounts[id] = mount
            }
            require(config.profile.disabled.all { it in mounts }) { "profile disables unknown plugin ids" }
            val context = Context()
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
                return KcodePluginRuntime(context, bootstrap, records, inventory, external).also { it.settle() }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { external?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                    runCatching { context.fiber.dispose() }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }
}

private val BootstrapIds = setOf("core.plugin-inventory", "core.loader")

private fun builtinDescriptor(id: String, vararg capabilities: String) =
    PluginDescriptor(id, "builtin", "built-in", capabilities.toSet())

private fun <C> builtin(id: String, plugin: Plugin<C>, config: C, vararg capabilities: String) =
    kcodePlugin(builtinDescriptor(id, *capabilities), plugin, config)

private fun defaultPlugins(config: KcodePluginRuntimeConfig): List<KcodePluginMount> = listOf(
    builtin("core.tools", ToolsServicePlugin, Unit, "tools"),
    builtin("core.system-prompt", SystemPromptServicePlugin, Unit, "systemPrompt"),
    builtin("core.llm", LlmServicePlugin, Unit, "llm"),
    builtin("core.continuations", ContinuationServicePlugin, Unit, "continuations"),
    builtin("provider.subagents.in-process", InProcessSubagentProviderPlugin, Unit, "subagents"),
    builtin("provider.llm.koog", DefaultModelAdaptersPlugin, Unit, "llm"),
    builtin("provider.prompt.default", DefaultSystemPromptPlugin, Unit, "systemPrompt"),
    builtin("provider.skills.platform", SkillServicePlugin, SkillServicePluginConfig(config.skillRuntime), "skills"),
    builtin("consumer.tools.subagent", SubagentToolConsumerPlugin, Unit, "subagent", "tools"),
    builtin("consumer.tools.goal", GoalToolConsumerPlugin, Unit, "goal", "tools"),
    builtin("consumer.tools.schedule", ScheduledTaskToolConsumerPlugin, Unit, "schedule", "tools"),
    builtin("provider.continuation.subagent", SubagentContinuationPlugin, Unit, "subagent", "continuations"),
    builtin("provider.continuation.goal", GoalContinuationPlugin, Unit, "goal", "continuations"),
    builtin("provider.interaction.platform", InteractionServicePlugin, InteractionPluginConfig(config.interactionPolicy), "interaction"),
    builtin("provider.settings.platform", SettingsProviderPlugin, config.settingsStore, "settings"),
    builtin("provider.history.platform", HistoryProviderPlugin, config.historyRepository, "history"),
    builtin("provider.artifacts.platform", ArtifactsProviderPlugin, config.artifactRepository, "artifacts"),
    builtin("provider.web-containers.platform", WebContainersProviderPlugin, config.webContainerController, "webContainers"),
    builtin("provider.agent-loop.koog", KoogAgentLoopPlugin, AgentLoopPluginConfig(config.conversationOverlayController), "agents", "agentLoop"),
    builtin("provider.ui.compose", ApplicationUiPlugin, DefaultApplicationRenderer, "applicationUi"),
)

data class KcodePluginDiagnostics(
    val plugins: List<PluginDescriptor>,
    val toolContributions: List<String>,
    val promptSections: List<String>,
    val modelAdapters: List<String>,
)
