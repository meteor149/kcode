package ai.meteor.kcode

import ai.meteor.kcode.chat.SubAgentStatus
import ai.meteor.kcode.chat.ToolUseEvent

const val RootAgentPath = "/root"

data class SubAgentLaunch(
    val path: String,
    val parentPath: String,
    val taskName: String,
    val prompt: String,
    val inheritedContext: String,
)

data class SubAgentSnapshot(
    val path: String,
    val parentPath: String,
    val taskName: String,
    val status: SubAgentStatus,
    val currentTool: String?,
)

interface SubagentCoordinator {
    suspend fun spawn(callerPath: String, taskName: String, message: String, forkTurns: String?): String
    suspend fun sendMessage(callerPath: String, target: String, message: String): String
    suspend fun followupTask(callerPath: String, target: String, message: String): String
    suspend fun interrupt(callerPath: String, target: String): String
    suspend fun list(callerPath: String, pathPrefix: String?): String
    suspend fun waitForUpdate(callerPath: String): String
    suspend fun drainMailbox(agentPath: String): String
    suspend fun continuationAfterRootResponse(): String?
    suspend fun onToolUse(agentPath: String, event: ToolUseEvent)
    suspend fun shutdown()
}
