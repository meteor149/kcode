package ai.meteor.kcode

interface AgentWorkspace {
    suspend fun readText(path: String): String
    suspend fun writeText(path: String, content: String)
    suspend fun list(path: String): List<AgentWorkspaceEntry>

    suspend fun canonicalize(path: String): String
}

data class AgentWorkspaceEntry(
    val path: String,
    val directory: Boolean,
    val size: Long,
)
