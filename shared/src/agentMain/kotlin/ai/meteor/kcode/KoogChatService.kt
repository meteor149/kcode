package ai.meteor.kcode

import ai.meteor.kcode.chat.ChatAvailability
import ai.meteor.kcode.chat.ChatService
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
        onDelta: suspend (String) -> Unit,
    ): String {
        require(configuration.provider == ModelProvider.Ollama || configuration.apiKey.isNotBlank()) {
            "请先在设置中添加 API Key。"
        }

        val runtime = createAgentModelRuntime(configuration, httpClientFactory)
        val skillTurn = skillRuntime?.prepareTurn(prompt)

        val strategy = StreamingToolStrategy(
            tools = additionalTools,
            model = runtime.model,
            permissionModeProvider = toolPermissionModeProvider,
            approver = toolCallApprover,
            onToolUse = onToolUse,
            onDelta = onDelta,
        ).create()

        val agent = AIAgent(
            promptExecutor = MultiLLMPromptExecutor(runtime.client),
            llmModel = runtime.model,
            strategy = strategy,
            systemPrompt = buildKcodeSystemPrompt(skillTurn?.catalogInstructions),
            temperature = configuration.temperature,
            toolRegistry = additionalTools,
        )
        val conversationContext = buildContext(history, prompt)
        return agent.run(skillTurn?.prependTo(conversationContext) ?: conversationContext)
    }
}
