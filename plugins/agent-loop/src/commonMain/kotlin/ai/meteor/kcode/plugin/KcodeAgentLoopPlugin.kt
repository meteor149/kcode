package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentLifecycle
import ai.meteor.kcode.plugin.agentloop.KoogChatService
import ai.meteor.kcode.ToolExecutionLifecycle
import ai.meteor.kcode.ToolExecutionRequest
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.api.AgentTurnFinished
import ai.meteor.kcode.plugin.api.AgentTurnStarted
import ai.meteor.kcode.plugin.api.KcodeAgentEvents
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeConversationOverlays
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeSystemPrompt
import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.KcodeContinuations
import ai.meteor.kcode.plugin.api.KcodeSubagents
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeToolEvents
import ai.meteor.kcode.plugin.api.ToolExecutionFinished
import ai.meteor.kcode.plugin.api.PromptAssemblyRequest
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object KoogAgentLoopPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-agent-loop-koog"
    override val inject: Dependencies = dependencies(
        KcodeTools.Key,
        KcodeSystemPrompt.Key,
        KcodeLlm.Key,
        KcodeInteraction.Key,
        KcodeSkills.Key,
        KcodeContinuations.Key,
        KcodeSubagents.Key,
        KcodeConversationOverlays.Key,
    )

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val interaction = ctx.require(KcodeInteraction.Key).policy
        val lifecycle = CordisAgentLifecycle(ctx)
        val owner = PluginOperationOwner("Koog agent provider")
        effect.collect { owner.close() }
        KcodeAgents(
            ctx = ctx,
            chatService = OwnedAgentChatService(
                delegate = KoogChatService(
                    additionalToolsProvider = { toolContext -> ctx.require(KcodeTools.Key).snapshot(toolContext) },
                    modelRuntimeProvider = { configuration, factory ->
                        ctx.require(KcodeLlm.Key).resolve(configuration).create(configuration, factory)
                    },
                    systemPromptProvider = { skillCatalogInstructions, multiAgentInstructions ->
                        ctx.require(KcodeSystemPrompt.Key).render(
                            PromptAssemblyRequest(skillCatalogInstructions, multiAgentInstructions),
                        )
                    },
                    lifecycle = lifecycle,
                    toolLifecycle = CordisToolLifecycle(ctx),
                    continuationProvider = { continuation ->
                        ctx.require(KcodeContinuations.Key).next(continuation)
                    },
                    subagentCoordinatorFactory = ctx.require(KcodeSubagents.Key).factory,
                    toolPermissionModeProvider = interaction.permissionModeProvider,
                    toolCallApprover = interaction.approver,
                    skillRuntime = ctx.require(KcodeSkills.Key).runtime,
                    conversationOverlayProvider = { ctx.require(KcodeConversationOverlays.Key).current() },
                ),
                owner = owner,
            ),
        )
    }
}

internal class OwnedAgentChatService(
    private val delegate: ChatService,
    private val owner: PluginOperationOwner,
) : ChatService {
    override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String =
        owner.run { delegate.reply(configuration, history, prompt) }

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
    ): String = owner.run {
        delegate.replyStreaming(
            configuration, history, prompt, goalSession, scheduledTaskSession,
            scheduledTaskCompletionSession, onToolUse, onSubAgent, onDelta,
        )
    }
}

private class CordisToolLifecycle(private val ctx: Context) : ToolExecutionLifecycle {
    override suspend fun beforeExecute(request: ToolExecutionRequest): ToolExecutionRequest =
        ctx.waterfallEvent(KcodeToolEvents.PreExecute, request) { request } ?: request

    override suspend fun afterExecute(
        request: ToolExecutionRequest,
        result: ReceivedToolResult,
    ): ReceivedToolResult = ctx.waterfallEvent(
        KcodeToolEvents.PostExecute,
        ToolExecutionFinished(request, result),
    ) { result } ?: result
}

private class CordisAgentLifecycle(private val ctx: Context) : AgentLifecycle {
    override suspend fun beforeTurn(prompt: String): String =
        ctx.waterfallEvent(KcodeAgentEvents.PreStep, prompt) { prompt } ?: prompt

    override suspend fun onTurnStarted(prompt: String) {
        ctx.parallelEvent(KcodeAgentEvents.TurnStarted, AgentTurnStarted(prompt))
    }

    override suspend fun onTurnFinished(response: String?, error: Throwable?) {
        ctx.parallelEvent(KcodeAgentEvents.TurnFinished, AgentTurnFinished(response, error))
    }
}
