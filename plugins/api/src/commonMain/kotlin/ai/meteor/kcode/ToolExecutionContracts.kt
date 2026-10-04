package ai.meteor.kcode

import ai.koog.agents.core.environment.ReceivedToolResult

data class ToolExecutionRequest(
    val name: String,
    val input: String,
    val description: String,
    val deniedReason: String? = null,
)

interface ToolExecutionLifecycle {
    suspend fun beforeExecute(request: ToolExecutionRequest): ToolExecutionRequest = request
    suspend fun afterExecute(request: ToolExecutionRequest, result: ReceivedToolResult): ReceivedToolResult = result

    object None : ToolExecutionLifecycle
}
