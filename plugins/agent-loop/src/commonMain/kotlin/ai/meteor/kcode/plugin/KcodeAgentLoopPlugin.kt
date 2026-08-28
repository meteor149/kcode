package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentLifecycle
import ai.meteor.kcode.KoogChatService
import ai.meteor.kcode.ToolExecutionLifecycle
import ai.meteor.kcode.ToolExecutionRequest
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.meteor.kcode.plugin.api.AgentTurnFinished
import ai.meteor.kcode.plugin.api.AgentTurnStarted
import ai.meteor.kcode.plugin.api.KcodeAgentEvents
import ai.meteor.kcode.plugin.api.KcodeAgents
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
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

data class AgentLoopPluginConfig(
    val conversationOverlayController: ai.meteor.kcode.AgentConversationOverlayController? = null,
)

object KoogAgentLoopPlugin : Plugin<AgentLoopPluginConfig> {
    override val name = "kcode-agent-loop-koog"
    override val inject: Dependencies = dependencies(
        KcodeTools.Key,
        KcodeSystemPrompt.Key,
        KcodeLlm.Key,
        KcodeInteraction.Key,
        KcodeSkills.Key,
        KcodeContinuations.Key,
        KcodeSubagents.Key,
    )

    override suspend fun apply(ctx: Context, config: AgentLoopPluginConfig, effect: EffectScope) {
        val interaction = ctx.require(KcodeInteraction.Key).policy
        val lifecycle = CordisAgentLifecycle(ctx)
        KcodeAgents(
            ctx = ctx,
            chatService = KoogChatService(
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
                conversationOverlayController = config.conversationOverlayController,
            ),
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
