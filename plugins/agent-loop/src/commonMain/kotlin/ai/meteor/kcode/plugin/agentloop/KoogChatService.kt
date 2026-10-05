package ai.meteor.kcode.plugin.agentloop

import ai.meteor.kcode.AgentLifecycle
import ai.meteor.kcode.AgentToolContext
import ai.meteor.kcode.AgentContinuationContext
import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.SubagentCoordinator
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.ToolExecutionLifecycle
import ai.meteor.kcode.RootAgentPath
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.skill.SkillRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlin.time.TimeSource

class KoogChatService(
    private val httpClientFactory: KoogHttpClient.Factory = KtorKoogHttpClient.Factory(),
    private val additionalToolsProvider: suspend (AgentToolContext) -> ToolRegistry,
    private val modelRuntimeProvider: suspend (ModelConfiguration, KoogHttpClient.Factory) -> AgentModelRuntime,
    private val systemPromptProvider: suspend (String?, String) -> String,
    private val lifecycle: AgentLifecycle = AgentLifecycle.None,
    private val toolLifecycle: ToolExecutionLifecycle = ToolExecutionLifecycle.None,
    private val continuationProvider: suspend (AgentContinuationContext) -> String?,
    private val subagentCoordinatorFactory: SubagentCoordinatorFactory? = null,
    private val toolPermissionModeProvider: suspend () -> ToolPermissionMode,
    private val toolCallApprover: ToolCallApprover,
    private val skillRuntime: SkillRuntime? = null,
    private val conversationOverlayProvider: suspend () -> AgentConversationOverlayController? = { null },
    private val skillRuntimeProvider: suspend () -> SkillRuntime? = { skillRuntime },
    private val subagentCoordinatorFactoryProvider: suspend () -> SubagentCoordinatorFactory? = { subagentCoordinatorFactory },
) : ChatService {
    override suspend fun reply(
        configuration: ModelConfiguration,
        history: List<ChatMessage>,
        prompt: String,
    ): String = replyStreaming(configuration, history, prompt, onDelta = { })

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
    ): String = coroutineScope {
        val admittedPrompt = lifecycle.beforeTurn(prompt)
        lifecycle.onTurnStarted(admittedPrompt)
        val conversationContext = buildContext(history, admittedPrompt)
        val overlayTranscript = ConversationOverlayTranscript(history, admittedPrompt)
        var overlayTurn: AgentConversationOverlayTurn? = null
        val goalTurnStart = TimeSource.Monotonic.markNow()
        var accountedSeconds = 0L
        val skillRuntime = skillRuntimeProvider()
        val subagentCoordinatorFactory = subagentCoordinatorFactoryProvider()
        var coordinator: SubagentCoordinator? = null
        coordinator = subagentCoordinatorFactory?.create(
            scope = this,
            rootContext = conversationContext,
            runAgent = { launch ->
                val childCoordinator = requireNotNull(coordinator)
                val childSkillTurn = skillRuntime?.prepareTurn(launch.prompt)
                val childContext = buildString {
                    if (launch.inheritedContext.isNotBlank()) {
                        append(launch.inheritedContext)
                        append("\n\n")
                    }
                    append("Your canonical task name is ").append(launch.path).append(".\n")
                    append("Your parent agent is ").append(launch.parentPath).append(".\n\n")
                    append(launch.prompt)
                }
                val childInput = childSkillTurn?.let { appendSelectedSkillFragments(it, childContext) } ?: childContext
                runAgent(
                    configuration = configuration,
                    input = childInput,
                    agentPath = launch.path,
                    multiAgentInstructions = subAgentInstructions(subagentCoordinatorFactory.maxConcurrency),
                    coordinator = childCoordinator,
                    goalSession = goalSession,
                    scheduledTaskSession = scheduledTaskSession,
                    scheduledTaskCompletionSession = null,
                    onToolUse = { event -> childCoordinator.onToolUse(launch.path, event) },
                    onDelta = {},
                    continuationAfterResponse = { null },
                    skillCatalogInstructions = childSkillTurn?.catalogInstructions,
                )
            },
            onEvent = { event ->
                overlayTranscript.apply(event)
                overlayTurn?.update(overlayTranscript.snapshot())
                onSubAgent(event)
            },
        )
        try {
            overlayTurn = conversationOverlayProvider()?.startTurn(overlayTranscript.snapshot())
            val skillTurn = skillRuntime?.prepareTurn(admittedPrompt)
            runAgent(
                configuration = configuration,
                input = skillTurn?.let { appendSelectedSkillFragments(it, conversationContext) } ?: conversationContext,
                agentPath = RootAgentPath,
                multiAgentInstructions = subagentCoordinatorFactory?.let { rootMultiAgentInstructions(it.maxConcurrency) }.orEmpty(),
                coordinator = coordinator,
                goalSession = goalSession,
                scheduledTaskSession = scheduledTaskSession,
                scheduledTaskCompletionSession = scheduledTaskCompletionSession,
                onToolUse = { event ->
                    overlayTranscript.apply(event)
                    overlayTurn?.update(overlayTranscript.snapshot())
                    onToolUse(event)
                },
                onDelta = { delta ->
                    overlayTranscript.appendResponse(delta)
                    overlayTurn?.update(overlayTranscript.snapshot())
                    onDelta(delta)
                },
                continuationAfterResponse = {
                    continuationProvider(
                        AgentContinuationContext(
                            subagentContinuation = { coordinator?.continuationAfterRootResponse() },
                            goalContinuation = {
                                goalSession?.continuationPrompt()
                            },
                        ),
                    )
                },
                onUsage = { tokens ->
                    val elapsed = goalTurnStart.elapsedNow().inWholeSeconds
                    val elapsedDelta = (elapsed - accountedSeconds).coerceAtLeast(0L)
                    accountedSeconds = elapsed
                    goalSession?.recordUsage(tokens.toLong(), elapsedDelta)
                },
                skillCatalogInstructions = skillTurn?.catalogInstructions,
            ).also { lifecycle.onTurnFinished(it, null) }
        } catch (error: Throwable) {
            lifecycle.onTurnFinished(null, error)
            throw error
        } finally {
            withContext(NonCancellable) {
                try { overlayTurn?.finish() } finally { coordinator?.shutdown() }
            }
        }
    }

    private suspend fun runAgent(
        configuration: ModelConfiguration,
        input: String,
        agentPath: String,
        multiAgentInstructions: String,
        coordinator: SubagentCoordinator?,
        goalSession: GoalSession?,
        scheduledTaskSession: ScheduledTaskSession?,
        scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
        onToolUse: suspend (ToolUseEvent) -> Unit,
        onDelta: suspend (String) -> Unit,
        continuationAfterResponse: suspend () -> String?,
        onUsage: suspend (Int) -> Unit = {},
        skillCatalogInstructions: String? = null,
    ): String {
        val runtime = modelRuntimeProvider(configuration, httpClientFactory)
        var executingAgent: AIAgent<String, String>? = null
        var failure: Throwable? = null
        try {
            val tools = additionalToolsProvider(
                AgentToolContext(
                    agentPath = agentPath,
                    coordinator = coordinator,
                    goalSession = goalSession,
                    scheduledTaskSession = scheduledTaskSession,
                    scheduledTaskCompletionSession = scheduledTaskCompletionSession,
                ),
            )
            val strategy = StreamingToolStrategy(
                tools = tools,
                model = runtime.model,
                permissionModeProvider = toolPermissionModeProvider,
                approver = toolCallApprover,
                onToolUse = onToolUse,
                onDelta = onDelta,
                additionalContextProvider = { coordinator?.drainMailbox(agentPath).orEmpty() },
                continuationAfterResponse = continuationAfterResponse,
                onUsage = onUsage,
                toolLifecycle = toolLifecycle,
            ).create()
            val agent = AIAgent(
                promptExecutor = MultiLLMPromptExecutor(runtime.client),
                llmModel = runtime.model,
                strategy = strategy,
                systemPrompt = systemPromptProvider(skillCatalogInstructions, availableMultiAgentInstructions(tools, multiAgentInstructions)),
                temperature = configuration.temperature,
                toolRegistry = tools,
            )
            executingAgent = agent
            return agent.run(input)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try {
                    executingAgent?.close()
                } catch (error: Throwable) {
                    cleanupFailure = error
                }
                try {
                    runtime.client.close()
                } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
                }
                cleanupFailure?.let { error ->
                    if (failure == null) throw error else failure.addSuppressed(error)
                }
            }
        }

    }
}
