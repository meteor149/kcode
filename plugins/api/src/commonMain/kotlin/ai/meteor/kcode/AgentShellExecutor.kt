package ai.meteor.kcode

interface AgentShellExecutor {
    suspend fun execute(command: String, workingDirectory: String?): ExecutionResult

    data class ExecutionResult(
        val output: String,
        val exitCode: Int?,
    )
}
