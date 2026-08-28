package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentRuntimeOwner
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodePluginInventory
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeSystemPrompt
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.skill.SkillRuntime
import org.cordis.Context
import org.cordis.Fiber
import org.cordis.Plugin
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin

interface DynamicPluginController : AgentPluginManager {
    suspend fun close()
}

fun interface DynamicPluginControllerFactory {
    fun create(context: Context, loader: Loader, inventory: KcodePluginInventory): DynamicPluginController
}

interface KcodePluginMount {
    val descriptor: PluginDescriptor
    suspend fun mount(context: Context): Fiber<*>
}

fun <C> kcodePlugin(
    descriptor: PluginDescriptor,
    plugin: Plugin<C>,
    config: C,
): KcodePluginMount = object : KcodePluginMount {
    override val descriptor = descriptor
    override suspend fun mount(context: Context): Fiber<*> = context.plugin(plugin, config).await()
}

data class KcodePluginRuntimeConfig(
    val interactionPolicy: InteractionPolicy,
    val skillRuntime: SkillRuntime? = null,
    val conversationOverlayController: ai.meteor.kcode.AgentConversationOverlayController? = null,
    val featurePlugins: List<KcodePluginMount> = emptyList(),
    val dynamicPluginControllerFactory: DynamicPluginControllerFactory? = null,
)

class KcodePluginRuntime private constructor(
    private val context: Context,
    private val fibers: List<Fiber<*>>,
    val chatService: ChatService,
    val inventory: KcodePluginInventory,
    val dynamicPlugins: DynamicPluginController?,
) : AgentRuntimeOwner {
    suspend fun diagnostics(): KcodePluginDiagnostics = KcodePluginDiagnostics(
        plugins = inventory.snapshot(),
        toolContributions = context.require(KcodeTools.Key).contributionIds(),
        promptSections = context.require(KcodeSystemPrompt.Key).sectionIds(),
        modelAdapters = context.require(KcodeLlm.Key).adapterIds(),
    )

    override suspend fun close() {
        var failure: Throwable? = null
        try {
            dynamicPlugins?.close()
        } catch (error: Throwable) {
            failure = error
        }
        fibers.asReversed().forEach { fiber ->
            try {
                fiber.dispose()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        suspend fun create(config: KcodePluginRuntimeConfig): KcodePluginRuntime {
            val context = Context()
            val fibers = mutableListOf<Fiber<*>>()
            suspend fun <C> mount(
                id: String,
                plugin: Plugin<C>,
                pluginConfig: C,
                capabilities: Set<String>,
            ): Fiber<C> {
                val fiber = context.plugin(plugin, pluginConfig).await()
                fibers += fiber
                context.require(KcodePluginInventory.Key).publish(
                    PluginDescriptor(id, "builtin", "built-in", capabilities),
                )
                return fiber
            }

            try {
                val inventoryFiber = context.plugin(PluginInventoryServicePlugin, Unit).await()
                fibers += inventoryFiber
                val inventory = context.require(KcodePluginInventory.Key)
                inventory.publish(
                    PluginDescriptor("core.plugin-inventory", "builtin", "built-in", setOf("pluginInventory")),
                )
                mount("core.loader", LoaderPlugin, LoaderConfig(), setOf("loader"))
                mount("core.tools", ToolsServicePlugin, Unit, setOf("tools"))
                mount("core.system-prompt", SystemPromptServicePlugin, Unit, setOf("systemPrompt"))
                mount("core.llm", LlmServicePlugin, Unit, setOf("llm"))
                mount("core.continuations", ContinuationServicePlugin, Unit, setOf("continuations"))
                mount(
                    "provider.subagents.in-process",
                    InProcessSubagentProviderPlugin,
                    Unit,
                    setOf("subagents"),
                )
                mount("provider.llm.koog", DefaultModelAdaptersPlugin, Unit, setOf("llm"))
                mount("provider.prompt.default", DefaultSystemPromptPlugin, Unit, setOf("systemPrompt"))
                mount(
                    "provider.skills.platform",
                    SkillServicePlugin,
                    SkillServicePluginConfig(config.skillRuntime),
                    setOf("skills"),
                )
                mount("consumer.tools.subagent", SubagentToolConsumerPlugin, Unit, setOf("subagent", "tools"))
                mount("consumer.tools.goal", GoalToolConsumerPlugin, Unit, setOf("goal", "tools"))
                mount("consumer.tools.schedule", ScheduledTaskToolConsumerPlugin, Unit, setOf("schedule", "tools"))
                mount(
                    "provider.continuation.subagent",
                    SubagentContinuationPlugin,
                    Unit,
                    setOf("subagent", "continuations"),
                )
                mount(
                    "provider.continuation.goal",
                    GoalContinuationPlugin,
                    Unit,
                    setOf("goal", "continuations"),
                )
                mount(
                    "provider.interaction.platform",
                    InteractionServicePlugin,
                    InteractionPluginConfig(config.interactionPolicy),
                    setOf("interaction"),
                )
                config.featurePlugins.forEach { feature ->
                    fibers += feature.mount(context)
                    inventory.publish(feature.descriptor)
                }
                mount(
                    "provider.agent-loop.koog",
                    KoogAgentLoopPlugin,
                    AgentLoopPluginConfig(config.conversationOverlayController),
                    setOf("agents", "agentLoop"),
                )
                val dynamicPlugins = config.dynamicPluginControllerFactory?.create(
                    context,
                    context.require(Loader.Key),
                    inventory,
                )
                return KcodePluginRuntime(
                    context = context,
                    fibers = fibers,
                    chatService = context.require(KcodeAgents.Key).chatService,
                    inventory = inventory,
                    dynamicPlugins = dynamicPlugins,
                )
            } catch (error: Throwable) {
                fibers.asReversed().forEach { runCatching { it.dispose() } }
                throw error
            }
        }
    }
}

data class KcodePluginDiagnostics(
    val plugins: List<PluginDescriptor>,
    val toolContributions: List<String>,
    val promptSections: List<String>,
    val modelAdapters: List<String>,
)
