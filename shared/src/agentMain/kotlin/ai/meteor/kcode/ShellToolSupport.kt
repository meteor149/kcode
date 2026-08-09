package ai.meteor.kcode

internal data class ShellCommandRequest(
    val command: String,
    val relativeWorkingDirectory: String,
    val timeoutSeconds: Int,
)

internal fun normalizeShellCommandRequest(
    command: String,
    workingDirectory: String?,
    timeoutSeconds: Int,
): ShellCommandRequest {
    val normalizedCommand = command.trim()
    require(normalizedCommand.isNotEmpty()) { "Command must not be empty" }
    require(normalizedCommand.length <= MAX_SHELL_COMMAND_CHARS) { "Command is too long" }

    return ShellCommandRequest(
        command = normalizedCommand,
        relativeWorkingDirectory = normalizeWorkspaceRelativePath(workingDirectory),
        timeoutSeconds = timeoutSeconds.coerceIn(1, MAX_SHELL_TIMEOUT_SECONDS),
    )
}

private fun normalizeWorkspaceRelativePath(workingDirectory: String?): String {
    if (workingDirectory == null || workingDirectory == VIRTUAL_WORKSPACE_ROOT) return ""
    require(workingDirectory.startsWith("$VIRTUAL_WORKSPACE_ROOT/")) {
        "Working directory must be $VIRTUAL_WORKSPACE_ROOT or one of its descendants"
    }
    require('\\' !in workingDirectory && '\u0000' !in workingDirectory) {
        "Invalid workspace path"
    }
    val parts = workingDirectory.removePrefix("$VIRTUAL_WORKSPACE_ROOT/").split('/')
    require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "Working directory must be a normalized path inside $VIRTUAL_WORKSPACE_ROOT"
    }
    return parts.joinToString("/")
}

internal fun virtualWorkspacePath(relativePath: String): String =
    if (relativePath.isEmpty()) VIRTUAL_WORKSPACE_ROOT else "$VIRTUAL_WORKSPACE_ROOT/$relativePath"

private const val VIRTUAL_WORKSPACE_ROOT = "/workspace"
private const val MAX_SHELL_COMMAND_CHARS = 8_192
private const val MAX_SHELL_TIMEOUT_SECONDS = 20
