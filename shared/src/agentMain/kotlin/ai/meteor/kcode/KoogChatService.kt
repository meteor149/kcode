package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.goalContinuationPrompt
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.ToolUseEvent
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.buildContext
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.skill.SkillRuntime
import kotlinx.coroutines.coroutineScope
import kotlin.time.TimeSource

class KoogChatService(
    private val httpClientFactory: KoogHttpClient.Factory = KtorKoogHttpClient.Factory(),
    private val additionalTools: ToolRegistry = ToolRegistry { },
    private val additionalToolsProvider: suspend (AgentToolContext) -> ToolRegistry = { additionalTools },
    private val modelRuntimeProvider: suspend (ModelConfiguration, KoogHttpClient.Factory) -> AgentModelRuntime =
        ::createAgentModelRuntime,
    private val systemPromptProvider: suspend (String?, String) -> String =
        { skillCatalogInstructions, multiAgentInstructions ->
            buildKcodeSystemPrompt(skillCatalogInstructions, multiAgentInstructions)
        },
    private val lifecycle: AgentLifecycle = AgentLifecycle.None,
    private val toolLifecycle: ToolExecutionLifecycle = ToolExecutionLifecycle.None,
    private val continuationProvider: suspend (AgentContinuationContext) -> String? = { context ->
        context.subagentContinuation() ?: context.goalContinuation()
    },
    private val subagentCoordinatorFactory: SubagentCoordinatorFactory = SubagentCoordinatorFactory.Default,
    private val toolPermissionModeProvider: suspend () -> ToolPermissionMode = { ToolPermissionMode.Ask },
    private val toolCallApprover: ToolCallApprover = ToolCallApprover { false },
    private val skillRuntime: SkillRuntime? = null,
    private val conversationOverlayController: AgentConversationOverlayController? = null,
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
        require(configuration.provider == ModelProvider.Ollama || configuration.apiKey.isNotBlank()) {
            "请先在设置中添加 API Key。"
        }

        val admittedPrompt = lifecycle.beforeTurn(prompt)
        lifecycle.onTurnStarted(admittedPrompt)
        val conversationContext = buildContext(history, admittedPrompt)
        val overlayTranscript = ConversationOverlayTranscript(history, admittedPrompt)
        val overlayTurn = conversationOverlayController?.startTurn(overlayTranscript.snapshot())
        val goalTurnStart = TimeSource.Monotonic.markNow()
        var accountedSeconds = 0L
        lateinit var coordinator: SubagentCoordinator
        coordinator = subagentCoordinatorFactory.create(
            scope = this,
            rootContext = conversationContext,
            runAgent = { launch ->
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
                val childInput = childSkillTurn?.prependTo(childContext) ?: childContext
                runAgent(
                    configuration = configuration,
                    input = childInput,
                    agentPath = launch.path,
                    multiAgentInstructions = SubAgentInstructions,
                    coordinator = coordinator,
                    goalSession = goalSession,
                    scheduledTaskSession = scheduledTaskSession,
                    scheduledTaskCompletionSession = null,
                    onToolUse = { event -> coordinator.onToolUse(launch.path, event) },
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
            val skillTurn = skillRuntime?.prepareTurn(admittedPrompt)
            runAgent(
                configuration = configuration,
                input = skillTurn?.prependTo(conversationContext) ?: conversationContext,
                agentPath = RootAgentPath,
                multiAgentInstructions = RootMultiAgentInstructions,
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
                            subagentContinuation = coordinator::continuationAfterRootResponse,
                            goalContinuation = {
                                goalSession?.getGoal()
                                    ?.takeIf { it.status == ai.meteor.kcode.history.ThreadGoalStatus.Active }
                                    ?.let(::goalContinuationPrompt)
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
            overlayTurn?.finish()
            coordinator.shutdown()
        }
    }

    private suspend fun runAgent(
        configuration: ModelConfiguration,
        input: String,
        agentPath: String,
        multiAgentInstructions: String,
        coordinator: SubagentCoordinator,
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
        val tools = additionalToolsProvider(
            AgentToolContext(
                agentPath = agentPath,
                subagentTools = coordinator.toolsFor(agentPath),
                goalTools = goalSession?.let(::goalTools) ?: ToolRegistry { },
                scheduledTaskTools = scheduledTaskSession?.let(::scheduledTaskTools) ?: ToolRegistry { },
                scheduledTaskCompletionTools = scheduledTaskCompletionSession
                    ?.let(::scheduledTaskCompletionTools)
                    ?: ToolRegistry { },
            ),
        )
        val strategy = StreamingToolStrategy(
            tools = tools,
            model = runtime.model,
            permissionModeProvider = toolPermissionModeProvider,
            approver = toolCallApprover,
            onToolUse = onToolUse,
            onDelta = onDelta,
            additionalContextProvider = { coordinator.drainMailbox(agentPath) },
            continuationAfterResponse = continuationAfterResponse,
            onUsage = onUsage,
            toolLifecycle = toolLifecycle,
        ).create()
        val agent = AIAgent(
            promptExecutor = MultiLLMPromptExecutor(runtime.client),
            llmModel = runtime.model,
            strategy = strategy,
            systemPrompt = systemPromptProvider(skillCatalogInstructions, multiAgentInstructions),
            temperature = configuration.temperature,
            toolRegistry = tools,
        )
        return agent.run(input)
    }
}

interface AgentLifecycle {
    suspend fun beforeTurn(prompt: String): String = prompt
    suspend fun onTurnStarted(prompt: String) = Unit
    suspend fun onTurnFinished(response: String?, error: Throwable?) = Unit

    object None : AgentLifecycle
}

data class AgentToolContext(
    val agentPath: String,
    val subagentTools: ToolRegistry,
    val goalTools: ToolRegistry,
    val scheduledTaskTools: ToolRegistry,
    val scheduledTaskCompletionTools: ToolRegistry,
)

data class AgentContinuationContext(
    val subagentContinuation: suspend () -> String?,
    val goalContinuation: suspend () -> String?,
)

fun interface SubagentCoordinatorFactory {
    fun create(
        scope: kotlinx.coroutines.CoroutineScope,
        rootContext: String,
        runAgent: suspend (SubAgentLaunch) -> String,
        onEvent: suspend (SubAgentEvent) -> Unit,
    ): SubagentCoordinator

    object Default : SubagentCoordinatorFactory {
        override fun create(
            scope: kotlinx.coroutines.CoroutineScope,
            rootContext: String,
            runAgent: suspend (SubAgentLaunch) -> String,
            onEvent: suspend (SubAgentEvent) -> Unit,
        ): SubagentCoordinator = MultiAgentCoordinator(
            scope = scope,
            rootContext = rootContext,
            runAgent = runAgent,
            onEvent = onEvent,
        )
    }
}

internal suspend fun nextRootContinuation(
    coordinator: SubagentCoordinator,
    goalSession: GoalSession?,
): String? = coordinator.continuationAfterRootResponse()
    ?: goalSession?.getGoal()?.takeIf { it.status == ai.meteor.kcode.history.ThreadGoalStatus.Active }
        ?.let(::goalContinuationPrompt)
