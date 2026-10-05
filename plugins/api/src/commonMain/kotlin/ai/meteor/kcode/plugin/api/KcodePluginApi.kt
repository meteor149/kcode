package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.meteor.kcode.AgentContinuationContext
import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.AgentToolContext
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.ToolExecutionRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EventKey
import org.cordis.Service
import org.cordis.ServiceKey

/** Stable request passed to system-prompt contribution plugins. */
data class PromptAssemblyRequest(
    val skillCatalogInstructions: String?,
    val multiAgentInstructions: String,
)

/** One ordered, replaceable system-prompt contribution. */
data class PromptSection(
    val id: String,
    val order: Int = 0,
    val render: suspend (PromptAssemblyRequest) -> String,
)

/** Service Definition for model-facing Koog tool contributions. */
class KcodeTools(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val contributions = linkedMapOf<String, suspend (AgentToolContext) -> ToolRegistry>()

    suspend fun register(id: String, tools: ToolRegistry): Disposable {
        return register(id) { tools }
    }

    suspend fun register(
        id: String,
        provide: suspend (AgentToolContext) -> ToolRegistry,
    ): Disposable {
        require(id.isNotBlank()) { "tool contribution id must not be blank" }
        val owner = PluginOperationOwner("tool contribution '$id'")
        val registered: suspend (AgentToolContext) -> ToolRegistry = { context ->
            owner.run { ownTools(provide(context), owner) }
        }
        mutex.withLock {
            require(id !in contributions) { "tool contribution '$id' is already registered" }
            contributions[id] = registered
        }
        return Disposable {
            owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock { if (contributions[id] === registered) contributions.remove(id) }
                owner.close()
            }
        }
    }

    suspend fun snapshot(context: AgentToolContext): ToolRegistry {
        val providers = mutex.withLock { contributions.values.toList() }
        return providers.fold(ToolRegistry { }) { result, provide -> result + provide(context) }
    }

    suspend fun contributionIds(): List<String> = mutex.withLock { contributions.keys.toList() }

    companion object {
        val Key = ServiceKey<KcodeTools>("tools")
    }
}

/** Service Definition for deterministic system-prompt assembly. */
class KcodeSystemPrompt(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val sections = linkedMapOf<String, PromptSection>()

    suspend fun register(section: PromptSection): Disposable {
        require(section.id.isNotBlank()) { "prompt section id must not be blank" }
        val owner = PluginOperationOwner("prompt section '${section.id}'")
        val registered = section.copy(render = { request -> owner.run { section.render(request) } })
        mutex.withLock {
            require(section.id !in sections) { "prompt section '${section.id}' is already registered" }
            sections[section.id] = registered
        }
        return Disposable {
            owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock { if (sections[section.id] === registered) sections.remove(section.id) }
                owner.close()
            }
        }
    }

    suspend fun render(request: PromptAssemblyRequest): String {
        val snapshot = mutex.withLock { sections.values.sortedWith(compareBy(PromptSection::order, PromptSection::id)) }
        return snapshot.map { it.render(request).trim() }.filter(String::isNotEmpty).joinToString("\n\n")
    }

    suspend fun sectionIds(): List<String> = mutex.withLock { sections.keys.toList() }

    companion object {
        val Key = ServiceKey<KcodeSystemPrompt>("systemPrompt")
    }
}

/** create must finish bounded allocation and clean up partial resources before throwing. */
data class ModelAdapter(
    val id: String,
    val priority: Int = 0,
    val supports: (ModelConfiguration) -> Boolean,
    val create: suspend (ModelConfiguration, KoogHttpClient.Factory) -> AgentModelRuntime,
    val catalog: ModelProviderSpec? = null,
)

/** Service Definition and provider registry for model adapters. */
class KcodeLlm(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val adapters = linkedMapOf<String, ModelAdapter>()

    suspend fun register(adapter: ModelAdapter): Disposable {
        require(adapter.id.isNotBlank()) { "model adapter id must not be blank" }
        val catalog = adapter.catalog?.let { specification ->
            require(specification.displayName?.isNotBlank() != false) {
                "model provider display name must not be blank"
            }
            require(specification.models.isNotEmpty()) { "model catalog must not be empty" }
            require(specification.models.all {
                it.provider == specification.provider && it.id.isNotBlank() &&
                    it.defaultTemperature.isFinite() && it.defaultTemperature in 0.0..1.0
            }) { "model catalog contains invalid model metadata" }
            require(specification.models.map { it.id }.distinct().size == specification.models.size) {
                "model catalog contains duplicate model ids"
            }
            specification.copy(models = specification.models.toList())
        }
        val lifetime = ModelAdapterLifetime(adapter.id)
        val registered = adapter.copy(
            supports = { lifetime.isOpen && adapter.supports(it) },
            create = { configuration, factory ->
                lifetime.acquire { adapter.create(configuration, factory) }
            },
            catalog = catalog,
        )
        mutex.withLock {
            require(catalog == null || adapters.values.none { it.catalog?.provider == catalog.provider }) {
                "model provider '${catalog?.provider}' is already registered"
            }
            require(adapter.id !in adapters) { "model adapter '${adapter.id}' is already registered" }
            adapters[adapter.id] = registered
        }
        return Disposable {
            lifetime.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock {
                    if (adapters[adapter.id] === registered) adapters.remove(adapter.id)
                }
                lifetime.close()
            }
        }
    }

    suspend fun resolve(configuration: ModelConfiguration): ModelAdapter = mutex.withLock {
        adapters.values
            .filter { it.supports(configuration) }
            .maxWithOrNull(compareBy<ModelAdapter> { it.priority }.thenBy { it.id })
            ?: error("no model adapter supports ${configuration.provider.name}")
    }

    suspend fun catalog(): ModelCatalogSnapshot = mutex.withLock {
        ModelCatalogSnapshot(adapters.values.mapNotNull { it.catalog }
            .sortedWith(compareBy<ModelProviderSpec> { it.order }.thenBy { it.provider.name }))
    }

    suspend fun adapterIds(): List<String> = mutex.withLock { adapters.keys.toList() }

    companion object {
        val Key = ServiceKey<KcodeLlm>("llm")
    }
}

/** Optional configuration capability; schema and validation belong to its provider. */
interface ToolPermissionSettingsPolicy {
    fun resolve(settings: StoredAppSettings): ToolPermissionMode
    fun update(settings: StoredAppSettings, mode: ToolPermissionMode): StoredAppSettings
}

data class InteractionPolicy(
    val permissionModeProvider: suspend () -> ToolPermissionMode = { ToolPermissionMode.Ask },
    val approver: ToolCallApprover,
    val settings: ToolPermissionSettingsPolicy? = null,
)

/** Service Definition for human approval and permission policy. */
class KcodeInteraction(
    ctx: Context,
    val policy: InteractionPolicy,
) : Service<Unit>(ctx, Key) {
    companion object {
        val Key = ServiceKey<KcodeInteraction>("interaction")
    }
}

/** Service Definition for skill discovery and turn preparation. */
class KcodeSkills(
    ctx: Context,
    val runtime: SkillRuntime?,
) : Service<Unit>(ctx, Key) {
    companion object {
        val Key = ServiceKey<KcodeSkills>("skills")
    }
}

data class ContinuationPolicy(
    val id: String,
    val order: Int,
    val evaluate: suspend (AgentContinuationContext) -> String?,
)

/** Registry for policies that can owe another model step after a root response. */
class KcodeContinuations(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val policies = linkedMapOf<String, ContinuationPolicy>()

    suspend fun register(policy: ContinuationPolicy): Disposable {
        require(policy.id.isNotBlank()) { "continuation policy id must not be blank" }
        val owner = PluginOperationOwner("continuation policy '${policy.id}'")
        val registered = policy.copy(evaluate = { context -> owner.run { policy.evaluate(context) } })
        mutex.withLock {
            require(policy.id !in policies) { "continuation policy '${policy.id}' is already registered" }
            policies[policy.id] = registered
        }
        return Disposable {
            owner.requireCanClose()
            withContext(NonCancellable) {
                mutex.withLock { if (policies[policy.id] === registered) policies.remove(policy.id) }
                owner.close()
            }
        }
    }

    suspend fun next(context: AgentContinuationContext): String? {
        val snapshot = mutex.withLock { policies.values.sortedWith(compareBy(ContinuationPolicy::order, ContinuationPolicy::id)) }
        snapshot.forEach { policy -> policy.evaluate(context)?.takeIf(String::isNotBlank)?.let { return it } }
        return null
    }

    companion object {
        val Key = ServiceKey<KcodeContinuations>("continuations")
    }
}

/** Service Definition for constructing the live subagent coordinator. */
class KcodeSubagents(
    ctx: Context,
    val factory: SubagentCoordinatorFactory,
) : Service<Unit>(ctx, Key) {
    companion object {
        val Key = ServiceKey<KcodeSubagents>("subagents")
    }
}

/** Agent interface exposed to UI and other Consumers; the Koog loop is only one provider. */
class KcodeAgents(
    ctx: Context,
    val chatService: ChatService,
) : Service<Unit>(ctx, Key) {
    companion object {
        val Key = ServiceKey<KcodeAgents>("agents")
    }
}

data class AgentTurnStarted(val prompt: String)
data class AgentTurnFinished(val response: String?, val error: Throwable?)

/** Live extension events. Durable conversation facts continue to use the history repository. */
object KcodeAgentEvents {
    /** Waterfall: transform a turn's prompt; call next() to delegate or return to short-circuit. */
    val PreStep = EventKey<String, String>("agent/pre-step")
    /** Parallel: awaited observers of a turn starting; results are ignored. */
    val TurnStarted = EventKey<AgentTurnStarted, Unit>("agent/turn-started")
    /** Parallel: awaited observers of success or failure; results are ignored. */
    val TurnFinished = EventKey<AgentTurnFinished, Unit>("agent/turn-finished")
}

data class ToolExecutionFinished(
    val request: ToolExecutionRequest,
    val result: ReceivedToolResult,
)

object KcodeToolEvents {
    /** Waterfall: transform the tool request before execution; call next() to delegate. */
    val PreExecute = EventKey<ToolExecutionRequest, ToolExecutionRequest>("tools/pre-execute")
    /** Waterfall: transform the completed tool result; call next() to delegate. */
    val PostExecute = EventKey<ToolExecutionFinished, ReceivedToolResult>("tools/post-execute")
}

enum class PluginState { Active, Pending, Disabled, Failed }

data class PluginDescriptor(
    val id: String,
    val version: String,
    val source: String,
    val capabilities: Set<String>,
    val state: PluginState = PluginState.Active,
    val error: String? = null,
)

/** Read-only runtime inventory used by diagnostics and future plugin settings UI. */
class KcodePluginInventory(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, PluginDescriptor>()

    suspend fun publish(descriptor: PluginDescriptor) {
        require(descriptor.id.isNotBlank()) { "plugin id must not be blank" }
        mutex.withLock {
            require(descriptor.id !in entries) { "plugin '${descriptor.id}' is already published" }
            entries[descriptor.id] = descriptor
        }
    }

    suspend fun replace(descriptor: PluginDescriptor) {
        require(descriptor.id.isNotBlank()) { "plugin id must not be blank" }
        mutex.withLock {
            require(descriptor.id in entries) { "plugin '${descriptor.id}' is not published" }
            entries[descriptor.id] = descriptor
        }
    }

    suspend fun remove(id: String) {
        mutex.withLock { entries.remove(id) }
    }

    suspend fun snapshot(): List<PluginDescriptor> = mutex.withLock { entries.values.toList() }

    companion object {
        val Key = ServiceKey<KcodePluginInventory>("pluginInventory")
    }
}
