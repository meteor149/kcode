package ai.meteor.kcode

import ai.meteor.kcode.chat.SubAgentEvent
import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ToolUseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val DefaultMaxAgentConcurrency = 5

class MultiAgentCoordinator(
    private val scope: CoroutineScope,
    rootContext: String,
    private val maxConcurrency: Int = DefaultMaxAgentConcurrency,
    private val runAgent: suspend (SubAgentLaunch) -> String,
    private val onEvent: suspend (SubAgentEvent) -> Unit = {},
) : SubagentCoordinator {
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

    override suspend fun spawn(
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

    override suspend fun sendMessage(callerPath: String, target: String, message: String): String {
        val recipient = resolveTarget(callerPath, target)
        enqueueMessage(recipient, MailboxMessage("MESSAGE", callerPath, message.trim()))
        return "Message queued for $recipient."
    }

    override suspend fun followupTask(callerPath: String, target: String, message: String): String {
        val recipientPath = resolveTarget(callerPath, target)
        val normalizedMessage = message.trim()
        require(normalizedMessage.isNotEmpty()) { "message must not be empty" }
        val node = mutex.withLock { requireNotNull(agents[recipientPath]) }
        enqueueMessage(recipientPath, MailboxMessage("NEW_TASK", callerPath, normalizedMessage))
        val shouldStart = mutex.withLock { !node.status.isLive() }
        if (shouldStart) startTurn(node, normalizedMessage)
        return "Follow-up task delivered to $recipientPath."
    }

    override suspend fun interrupt(callerPath: String, target: String): String {
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

    override suspend fun list(callerPath: String, pathPrefix: String?): String {
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

    override suspend fun waitForUpdate(callerPath: String): String {
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

    override suspend fun drainMailbox(agentPath: String): String = mutex.withLock {
        val node = agents[agentPath] ?: return@withLock ""
        node.mailbox.toList().also { node.mailbox.clear() }.joinToString("\n\n") { it.render(agentPath) }
    }

    override suspend fun continuationAfterRootResponse(): String? {
        val hasLiveChildren = mutex.withLock {
            agents.values.any { it.path != RootAgentPath && it.status.isLive() }
        }
        if (!hasLiveChildren) return drainMailbox(RootAgentPath).takeIf(String::isNotBlank)
        return waitForUpdate(RootAgentPath).let { update ->
            "$update\n\nContinue coordinating the remaining agents and integrate their results before finishing."
        }
    }

    override suspend fun onToolUse(agentPath: String, event: ToolUseEvent) {
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

    override suspend fun shutdown() {
        val jobs = mutex.withLock { agents.values.mapNotNull(AgentNode::job) }
        check(currentCoroutineContext()[Job] !in jobs) { "A subagent cannot shut down its own coordinator" }
        withContext(NonCancellable) {
            jobs.forEach(Job::cancel)
            jobs.forEach { it.join() }
        }
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
