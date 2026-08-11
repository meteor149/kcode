package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatAvailability
import ai.meteor.kcode.chat.ChatService
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

class KoogChatService(
    private val httpClientFactory: KoogHttpClient.Factory = KtorKoogHttpClient.Factory(),
    private val additionalTools: ToolRegistry = ToolRegistry { },
    private val toolPermissionModeProvider: suspend () -> ToolPermissionMode = { ToolPermissionMode.Ask },
    private val toolCallApprover: ToolCallApprover = ToolCallApprover { false },
    private val skillRuntime: SkillRuntime? = null,
) : ChatService {
    override val availability: ChatAvailability? = null

    override suspend fun reply(
        configuration: ModelConfiguration,
        history: List<ChatMessage>,
        prompt: String,
    ): String = replyStreaming(configuration, history, prompt, onDelta = { })

    override suspend fun replyStreaming(
        configuration: ModelConfiguration,
        history: List<ChatMessage>,
        prompt: String,
        onToolUse: suspend (ToolUseEvent) -> Unit,
        onSubAgent: suspend (SubAgentEvent) -> Unit,
        onDelta: suspend (String) -> Unit,
    ): String = coroutineScope {
        require(configuration.provider == ModelProvider.Ollama || configuration.apiKey.isNotBlank()) {
            "请先在设置中添加 API Key。"
        }

        val conversationContext = buildContext(history, prompt)
        lateinit var coordinator: MultiAgentCoordinator
        coordinator = MultiAgentCoordinator(
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
                    onToolUse = { event -> coordinator.onToolUse(launch.path, event) },
                    onDelta = {},
                    continuationAfterResponse = { null },
                    skillCatalogInstructions = childSkillTurn?.catalogInstructions,
                )
            },
            onEvent = onSubAgent,
        )
        try {
            val skillTurn = skillRuntime?.prepareTurn(prompt)
            runAgent(
                configuration = configuration,
                input = skillTurn?.prependTo(conversationContext) ?: conversationContext,
                agentPath = RootAgentPath,
                multiAgentInstructions = RootMultiAgentInstructions,
                coordinator = coordinator,
                onToolUse = onToolUse,
                onDelta = onDelta,
                continuationAfterResponse = coordinator::continuationAfterRootResponse,
                skillCatalogInstructions = skillTurn?.catalogInstructions,
            )
        } finally {
            coordinator.shutdown()
        }
    }

    private suspend fun runAgent(
        configuration: ModelConfiguration,
        input: String,
        agentPath: String,
        multiAgentInstructions: String,
        coordinator: MultiAgentCoordinator,
        onToolUse: suspend (ToolUseEvent) -> Unit,
        onDelta: suspend (String) -> Unit,
        continuationAfterResponse: suspend () -> String?,
        skillCatalogInstructions: String? = null,
    ): String {
        val runtime = createAgentModelRuntime(configuration, httpClientFactory)
        val tools = additionalTools + coordinator.toolsFor(agentPath)
        val strategy = StreamingToolStrategy(
            tools = tools,
            model = runtime.model,
            permissionModeProvider = toolPermissionModeProvider,
            approver = toolCallApprover,
            onToolUse = onToolUse,
            onDelta = onDelta,
            additionalContextProvider = { coordinator.drainMailbox(agentPath) },
            continuationAfterResponse = continuationAfterResponse,
        ).create()
        val agent = AIAgent(
            promptExecutor = MultiLLMPromptExecutor(runtime.client),
            llmModel = runtime.model,
            strategy = strategy,
            systemPrompt = buildKcodeSystemPrompt(skillCatalogInstructions, multiAgentInstructions),
            temperature = configuration.temperature,
            toolRegistry = tools,
        )
        return agent.run(input)
    }
}
