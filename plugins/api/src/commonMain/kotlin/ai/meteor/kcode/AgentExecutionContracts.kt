package ai.meteor.kcode

import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.chat.ScheduledTaskSession
import ai.meteor.kcode.chat.ScheduledTaskCompletionSession
import ai.meteor.kcode.chat.SubAgentEvent

interface AgentLifecycle {
    suspend fun beforeTurn(prompt: String): String = prompt
    suspend fun onTurnStarted(prompt: String) = Unit
    suspend fun onTurnFinished(response: String?, error: Throwable?) = Unit

    object None : AgentLifecycle
}

data class AgentToolContext(
    val agentPath: String,
    /** Present only when the turn has an installed subagent capability. */
    val coordinator: SubagentCoordinator?,
    val goalSession: GoalSession?,
    val scheduledTaskSession: ScheduledTaskSession?,
    val scheduledTaskCompletionSession: ScheduledTaskCompletionSession?,
)

data class AgentContinuationContext(
    val subagentContinuation: suspend () -> String?,
    val goalContinuation: suspend () -> String?,
)

fun interface SubagentCoordinatorFactory {
    /** Advertised total slots, including root; null when the provider declares no capacity metadata. */
    val maxConcurrency: Int? get() = null

    fun create(
        scope: kotlinx.coroutines.CoroutineScope,
        rootContext: String,
        runAgent: suspend (SubAgentLaunch) -> String,
        onEvent: suspend (SubAgentEvent) -> Unit,
    ): SubagentCoordinator
}
