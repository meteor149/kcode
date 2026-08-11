package ai.meteor.kcode

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

interface AgentShellExecutor {
    suspend fun execute(command: String, workingDirectory: String?): ExecutionResult

    data class ExecutionResult(
        val output: String,
        val exitCode: Int?,
    )
}

class AgentShellTool(
    private val executor: AgentShellExecutor,
) : SimpleTool<AgentShellTool.Args>(
    argsType = typeToken<Args>(),
    name = "execute_shell_command",
    description = "Executes a shell command and returns its complete combined output and exit code.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The exact shell command line to execute")
        val command: String,
        @property:LLMDescription("Absolute working directory; defaults to /workspace")
        val workingDirectory: String? = null,
    )

    override suspend fun execute(args: Args): String {
        val result = executor.execute(
            command = args.command,
            workingDirectory = args.workingDirectory,
        )
        return buildString {
            if (result.output.isNotEmpty()) {
                appendLine(result.output)
            } else if (result.exitCode != null) {
                appendLine("(no output)")
            }
            result.exitCode?.let { append("Exit code: $it") }
        }.trimEnd()
    }
}
