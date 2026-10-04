package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal fun subagentTools(coordinator: SubagentCoordinator, agentPath: String): ToolRegistry = ToolRegistry {
    tool(SpawnAgentTool(coordinator, agentPath))
    tool(SendMessageTool(coordinator, agentPath))
    tool(FollowupTaskTool(coordinator, agentPath))
    tool(InterruptAgentTool(coordinator, agentPath))
    tool(ListAgentsTool(coordinator, agentPath))
    tool(WaitAgentTool(coordinator, agentPath))
}

private class SpawnAgentTool(
    private val coordinator: SubagentCoordinator,
    private val callerPath: String,
) : SimpleTool<SpawnAgentTool.Args>(
    argsType = typeToken<Args>(),
    name = "spawn_agent",
    description = """
        Spawns an agent to work on the specified task. If your current task is `/root/task1` and you use task_name `task_3`, the child is `/root/task1/task_3`.
        The spawned agent has the same tools and can spawn subagents. Only use this for a concrete, bounded subtask that can run independently alongside useful local work.
        Its final answer is delivered to the parent. The returned task name can be used with the other collaboration tools.
    """.trimIndent(),
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Task name using lowercase letters, digits, and underscores")
        @SerialName("task_name")
        val taskName: String,
        @property:LLMDescription("Initial plain-text task for the new agent")
        val message: String,
        @property:LLMDescription("Context turns to inherit: none, all, or a positive integer string; defaults to all")
        @SerialName("fork_turns")
        val forkTurns: String? = null,
    )

    override suspend fun execute(args: Args): String =
        coordinator.spawn(callerPath, args.taskName, args.message, args.forkTurns)
}

private class SendMessageTool(
    private val coordinator: SubagentCoordinator,
    private val callerPath: String,
) : SimpleTool<SendMessageTool.Args>(
    argsType = typeToken<Args>(),
    name = "send_message",
    description = "Send a message to an existing agent without triggering a new turn.",
) {
    @Serializable
    data class Args(val target: String, val message: String)

    override suspend fun execute(args: Args): String = coordinator.sendMessage(callerPath, args.target, args.message)
}

private class FollowupTaskTool(
    private val coordinator: SubagentCoordinator,
    private val callerPath: String,
) : SimpleTool<FollowupTaskTool.Args>(
    argsType = typeToken<Args>(),
    name = "followup_task",
    description = "Send a follow-up task to an existing non-root agent and trigger a turn when it is idle.",
) {
    @Serializable
    data class Args(val target: String, val message: String)

    override suspend fun execute(args: Args): String = coordinator.followupTask(callerPath, args.target, args.message)
}

private class InterruptAgentTool(
    private val coordinator: SubagentCoordinator,
    private val callerPath: String,
) : SimpleTool<InterruptAgentTool.Args>(
    argsType = typeToken<Args>(),
    name = "interrupt_agent",
    description = "Interrupt an agent's current turn and return its previous status. The agent remains reusable.",
) {
    @Serializable
    data class Args(val target: String)

    override suspend fun execute(args: Args): String = coordinator.interrupt(callerPath, args.target)
}

private class ListAgentsTool(
    private val coordinator: SubagentCoordinator,
    private val callerPath: String,
) : SimpleTool<ListAgentsTool.Args>(
    argsType = typeToken<Args>(),
    name = "list_agents",
    description = "List agents in the current root task tree, optionally filtered by task-path prefix.",
) {
    @Serializable
    data class Args(
        @SerialName("path_prefix")
        val pathPrefix: String? = null,
    )

    override suspend fun execute(args: Args): String = coordinator.list(callerPath, args.pathPrefix)
}

private class WaitAgentTool(
    private val coordinator: SubagentCoordinator,
    private val callerPath: String,
) : SimpleTool<WaitAgentTool.Args>(
    argsType = typeToken<Args>(),
    name = "wait_agent",
    description = "Wait for a mailbox or status update from any live agent. The wait ends when activity arrives.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = coordinator.waitForUpdate(callerPath)
}
