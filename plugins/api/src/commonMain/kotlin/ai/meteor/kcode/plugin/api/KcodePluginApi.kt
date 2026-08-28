package ai.meteor.kcode.plugin.api

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.AgentToolContext
import ai.meteor.kcode.AgentContinuationContext
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.skill.SkillRuntime
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.ToolExecutionRequest
import ai.koog.agents.core.environment.ReceivedToolResult
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
        mutex.withLock {
            require(id !in contributions) { "tool contribution '$id' is already registered" }
            contributions[id] = provide
        }
        return Disposable { mutex.withLock { contributions.remove(id) }; Unit }
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
        mutex.withLock {
            require(section.id !in sections) { "prompt section '${section.id}' is already registered" }
            sections[section.id] = section
        }
        return Disposable { mutex.withLock { sections.remove(section.id) }; Unit }
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

data class ModelAdapter(
    val id: String,
    val priority: Int = 0,
    val supports: (ModelConfiguration) -> Boolean,
    val create: (ModelConfiguration, KoogHttpClient.Factory) -> AgentModelRuntime,
)

/** Service Definition and provider registry for model adapters. */
class KcodeLlm(ctx: Context) : Service<Unit>(ctx, Key) {
    private val mutex = Mutex()
    private val adapters = linkedMapOf<String, ModelAdapter>()

    suspend fun register(adapter: ModelAdapter): Disposable {
        require(adapter.id.isNotBlank()) { "model adapter id must not be blank" }
        mutex.withLock {
            require(adapter.id !in adapters) { "model adapter '${adapter.id}' is already registered" }
            adapters[adapter.id] = adapter
        }
        return Disposable { mutex.withLock { adapters.remove(adapter.id) }; Unit }
    }

    suspend fun resolve(configuration: ModelConfiguration): ModelAdapter = mutex.withLock {
        adapters.values
            .filter { it.supports(configuration) }
            .maxWithOrNull(compareBy<ModelAdapter> { it.priority }.thenBy { it.id })
            ?: error("no model adapter supports ${configuration.provider.name}")
    }

    suspend fun adapterIds(): List<String> = mutex.withLock { adapters.keys.toList() }

    companion object {
        val Key = ServiceKey<KcodeLlm>("llm")
    }
}

data class InteractionPolicy(
    val permissionModeProvider: suspend () -> ToolPermissionMode,
    val approver: ToolCallApprover,
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
        mutex.withLock {
            require(policy.id !in policies) { "continuation policy '${policy.id}' is already registered" }
            policies[policy.id] = policy
        }
        return Disposable { mutex.withLock { policies.remove(policy.id) }; Unit }
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
    val PreStep = EventKey<String, String>("agent/pre-step")
    val TurnStarted = EventKey<AgentTurnStarted, Unit>("agent/turn-started")
    val TurnFinished = EventKey<AgentTurnFinished, Unit>("agent/turn-finished")
}

data class ToolExecutionFinished(
    val request: ToolExecutionRequest,
    val result: ReceivedToolResult,
)

object KcodeToolEvents {
    val PreExecute = EventKey<ToolExecutionRequest, ToolExecutionRequest>("tools/pre-execute")
    val PostExecute = EventKey<ToolExecutionFinished, ReceivedToolResult>("tools/post-execute")
}

enum class PluginState { Active, Failed }

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
