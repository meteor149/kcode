package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ToolUseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

internal data class SubAgentLaunch(
    val path: String,
    val parentPath: String,
    val taskName: String,
    val prompt: String,
    val inheritedContext: String,
)

internal data class SubAgentSnapshot(
    val path: String,
    val parentPath: String,
    val taskName: String,
    val status: SubAgentStatus,
    val currentTool: String?,
)

internal const val MaxAgentConcurrency = 5

internal class MultiAgentCoordinator(
    private val scope: CoroutineScope,
    rootContext: String,
    private val maxConcurrency: Int = MaxAgentConcurrency,
    private val runAgent: suspend (SubAgentLaunch) -> String,
    private val onEvent: suspend (SubAgentEvent) -> Unit = {},
) {
    private val mutex = Mutex()
    private val agents = mutableMapOf(
        RootAgentPath to AgentNode(
            path = RootAgentPath,
            parentPath = "",
            taskName = "root",
            turns = mutableListOf(rootContext),
            status = SubAgentStatus.Running,
        ),
    )

    fun toolsFor(agentPath: String): ToolRegistry = ToolRegistry {
        tool(SpawnAgentTool(this@MultiAgentCoordinator, agentPath))
        tool(SendMessageTool(this@MultiAgentCoordinator, agentPath))
        tool(FollowupTaskTool(this@MultiAgentCoordinator, agentPath))
        tool(InterruptAgentTool(this@MultiAgentCoordinator, agentPath))
        tool(ListAgentsTool(this@MultiAgentCoordinator, agentPath))
        tool(WaitAgentTool(this@MultiAgentCoordinator, agentPath))
    }

    suspend fun spawn(
        callerPath: String,
        taskName: String,
        message: String,
        forkTurns: String?,
    ): String {
        val normalizedTaskName = taskName.trim()
        require(TaskNameRegex.matches(normalizedTaskName)) {
            "task_name must contain only lowercase letters, digits, and underscores"
        }
        val normalizedMessage = message.trim()
        require(normalizedMessage.isNotEmpty()) { "message must not be empty" }
        val childPath = "$callerPath/$normalizedTaskName"
        val node = mutex.withLock {
            require(agents[childPath] == null) { "Agent already exists: $childPath" }
            val activeCount = agents.values.count { it.status.isLive() }
            require(activeCount < maxConcurrency) {
                "No concurrency slot available ($maxConcurrency agents maximum, including root)"
            }
            val parent = requireNotNull(agents[callerPath]) { "Unknown caller: $callerPath" }
            AgentNode(
                path = childPath,
                parentPath = callerPath,
                taskName = normalizedTaskName,
                turns = inheritedTurns(parent.turns, forkTurns).toMutableList(),
                status = SubAgentStatus.Pending,
            ).also { agents[childPath] = it }
        }
        onEvent(SubAgentEvent.Spawned(childPath, callerPath, normalizedTaskName, normalizedMessage))
        startTurn(node, normalizedMessage)
        return "task_name=$childPath\nstatus=pending"
    }

    suspend fun sendMessage(callerPath: String, target: String, message: String): String {
        val recipient = resolveTarget(callerPath, target)
        enqueueMessage(recipient, MailboxMessage("MESSAGE", callerPath, message.trim()))
        return "Message queued for $recipient."
    }

    suspend fun followupTask(callerPath: String, target: String, message: String): String {
        val recipientPath = resolveTarget(callerPath, target)
        val normalizedMessage = message.trim()
        require(normalizedMessage.isNotEmpty()) { "message must not be empty" }
        val node = mutex.withLock { requireNotNull(agents[recipientPath]) }
        enqueueMessage(recipientPath, MailboxMessage("NEW_TASK", callerPath, normalizedMessage))
        val shouldStart = mutex.withLock { !node.status.isLive() }
        if (shouldStart) startTurn(node, normalizedMessage)
        return "Follow-up task delivered to $recipientPath."
    }

    suspend fun interrupt(callerPath: String, target: String): String {
        val recipientPath = resolveTarget(callerPath, target)
        require(recipientPath != RootAgentPath) { "root is not a spawned agent" }
        require(recipientPath != callerPath) { "an agent cannot interrupt itself" }
        val (previous, job) = mutex.withLock {
            val node = requireNotNull(agents[recipientPath])
            node.status to node.job
        }
        job?.cancel()
        updateStatus(recipientPath, SubAgentStatus.Interrupted)
        return "target=$recipientPath\nprevious_status=${previous.name.lowercase()}"
    }

    suspend fun list(callerPath: String, pathPrefix: String?): String {
        mutex.withLock { require(agents.containsKey(callerPath)) { "Unknown caller: $callerPath" } }
        val snapshots = snapshots(pathPrefix)
        return if (snapshots.isEmpty()) {
            "No matching agents."
        } else {
            snapshots.joinToString("\n") { snapshot ->
                buildString {
                    append(snapshot.path).append(" — ").append(snapshot.status.name.lowercase())
                    snapshot.currentTool?.let { append(" — tool=").append(it) }
                }
            }
        }
    }

    suspend fun waitForUpdate(callerPath: String): String {
        drainMailbox(callerPath).takeIf(String::isNotBlank)?.let { return it }
        val activity = mutex.withLock { requireNotNull(agents[callerPath]).activity }
        setStatusWithoutActivity(callerPath, SubAgentStatus.Waiting)
        try {
            while (true) {
                activity.receive()
                drainMailbox(callerPath).takeIf(String::isNotBlank)?.let { return it }
                val finished = snapshots().filter {
                    it.path != callerPath && !it.status.isLive()
                }
                if (finished.isNotEmpty()) {
                    return finished.joinToString("\n") { "${it.path}: ${it.status.name.lowercase()}" }
                }
            }
        } finally {
            setStatusWithoutActivity(callerPath, SubAgentStatus.Running)
        }
    }

    suspend fun drainMailbox(agentPath: String): String = mutex.withLock {
        val node = agents[agentPath] ?: return@withLock ""
        node.mailbox.toList().also { node.mailbox.clear() }.joinToString("\n\n") { it.render(agentPath) }
    }

    suspend fun continuationAfterRootResponse(): String? {
        val hasLiveChildren = mutex.withLock {
            agents.values.any { it.path != RootAgentPath && it.status.isLive() }
        }
        if (!hasLiveChildren) return drainMailbox(RootAgentPath).takeIf(String::isNotBlank)
        return waitForUpdate(RootAgentPath).let { update ->
            "$update\n\nContinue coordinating the remaining agents and integrate their results before finishing."
        }
    }

    suspend fun onToolUse(agentPath: String, event: ToolUseEvent) {
        if (agentPath == RootAgentPath) return
        when (event) {
            is ToolUseEvent.Started -> updateStatus(agentPath, SubAgentStatus.Running, event.name)
            is ToolUseEvent.Updated -> Unit
            is ToolUseEvent.Finished -> updateStatus(agentPath, SubAgentStatus.Running, currentTool = null)
        }
    }

    suspend fun snapshots(pathPrefix: String? = null): List<SubAgentSnapshot> = mutex.withLock {
        agents.values
            .filter { it.path != RootAgentPath }
            .filter { pathPrefix == null || it.path.startsWith(pathPrefix) }
            .map { SubAgentSnapshot(it.path, it.parentPath, it.taskName, it.status, it.currentTool) }
            .sortedBy(SubAgentSnapshot::path)
    }

    suspend fun shutdown() {
        val jobs = mutex.withLock { agents.values.mapNotNull(AgentNode::job) }
        jobs.forEach(Job::cancel)
    }

    private suspend fun startTurn(node: AgentNode, message: String) {
        val inheritedContext = mutex.withLock {
            node.turns += "Assigned task: $message"
            node.turns.joinToString("\n\n")
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            updateStatus(node.path, SubAgentStatus.Running)
            try {
                val output = runAgent(
                    SubAgentLaunch(
                        path = node.path,
                        parentPath = node.parentPath,
                        taskName = node.taskName,
                        prompt = message,
                        inheritedContext = inheritedContext,
                    ),
                )
                mutex.withLock { node.turns += "Agent response: $output" }
                updateStatus(node.path, SubAgentStatus.Completed, output = output)
                enqueueMessage(node.parentPath, MailboxMessage("FINAL_ANSWER", node.path, output))
            } catch (_: CancellationException) {
                updateStatus(node.path, SubAgentStatus.Interrupted)
            } catch (error: Throwable) {
                val detail = error.message ?: error::class.simpleName.orEmpty()
                updateStatus(node.path, SubAgentStatus.Failed, output = detail)
                enqueueMessage(node.parentPath, MailboxMessage("FINAL_ANSWER", node.path, "Agent failed: $detail"))
            }
        }
        mutex.withLock { node.job = job }
        job.start()
    }

    private suspend fun enqueueMessage(path: String, message: MailboxMessage) {
        mutex.withLock { requireNotNull(agents[path]) { "Unknown target: $path" }.mailbox += message }
        signalActivity()
    }

    private suspend fun resolveTarget(callerPath: String, target: String): String {
        val normalized = target.trim()
        require(normalized.isNotEmpty()) { "target must not be empty" }
        val resolved = if (normalized.startsWith('/')) normalized else "$callerPath/$normalized"
        mutex.withLock { require(agents.containsKey(resolved)) { "Unknown target: $target" } }
        return resolved
    }

    private suspend fun updateStatus(
        path: String,
        status: SubAgentStatus,
        currentTool: String? = null,
        output: String? = null,
    ) {
        mutex.withLock {
            val node = requireNotNull(agents[path])
            node.status = status
            node.currentTool = currentTool
        }
        if (path != RootAgentPath) onEvent(SubAgentEvent.StatusChanged(path, status, currentTool, output))
        signalActivity()
    }

    private suspend fun setStatusWithoutActivity(path: String, status: SubAgentStatus) {
        val currentTool = mutex.withLock {
            val node = requireNotNull(agents[path])
            node.status = status
            node.currentTool
        }
        if (path != RootAgentPath) onEvent(SubAgentEvent.StatusChanged(path, status, currentTool))
    }

    private suspend fun signalActivity() {
        mutex.withLock { agents.values.map(AgentNode::activity) }.forEach { it.trySend(Unit) }
    }

    private fun inheritedTurns(turns: List<String>, forkTurns: String?): List<String> {
        val value = forkTurns?.trim()?.lowercase().orEmpty().ifEmpty { "all" }
        return when (value) {
            "none" -> emptyList()
            "all" -> turns
            else -> {
                val count = value.toIntOrNull()
                require(count != null && count > 0) {
                    "fork_turns must be `none`, `all`, or a positive integer string"
                }
                turns.takeLast(count)
            }
        }
    }

    private data class AgentNode(
        val path: String,
        val parentPath: String,
        val taskName: String,
        val turns: MutableList<String>,
        var status: SubAgentStatus,
        var currentTool: String? = null,
        var job: Job? = null,
        val mailbox: MutableList<MailboxMessage> = mutableListOf(),
        val activity: Channel<Unit> = Channel(Channel.CONFLATED),
    )

    private data class MailboxMessage(
        val type: String,
        val author: String,
        val payload: String,
    ) {
        fun render(recipient: String): String = """
            Message Type: $type
            Task name: $recipient
            Sender: $author
            Payload:
            $payload
        """.trimIndent()
    }

    private fun SubAgentStatus.isLive(): Boolean =
        this == SubAgentStatus.Pending || this == SubAgentStatus.Running || this == SubAgentStatus.Waiting

    private companion object {
        val TaskNameRegex = Regex("[a-z0-9_]+")
    }
}

private class SpawnAgentTool(
    private val coordinator: MultiAgentCoordinator,
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
    private val coordinator: MultiAgentCoordinator,
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
    private val coordinator: MultiAgentCoordinator,
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
    private val coordinator: MultiAgentCoordinator,
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
    private val coordinator: MultiAgentCoordinator,
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
    private val coordinator: MultiAgentCoordinator,
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
