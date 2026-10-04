package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.AgentShellExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class DesktopShellCommandExecutor(
    workspace: Path,
    private val delegate: AgentShellExecutor = JvmAgentShellExecutor(),
) : AgentShellExecutor {
    private val workspaceRoot = workspace.toRealPath()

    override suspend fun execute(
        command: String,
        workingDirectory: String?,
    ): AgentShellExecutor.ExecutionResult {
        val request = normalizeShellCommandRequest(command, workingDirectory)
        val directory = resolveWorkingDirectory(request.relativeWorkingDirectory)
        return delegate.execute(
            command = request.command,
            workingDirectory = directory.toString(),
        )
    }

    private fun resolveWorkingDirectory(relativePath: String): Path {
        val candidate = if (relativePath.isEmpty()) workspaceRoot else workspaceRoot.resolve(relativePath)
        val directory = candidate.toRealPath()
        require(directory.startsWith(workspaceRoot) && Files.isDirectory(directory)) {
            "Working directory does not exist inside /workspace: ${virtualWorkspacePath(relativePath)}"
        }
        return directory
    }
}

private class JvmAgentShellExecutor : AgentShellExecutor {
    override suspend fun execute(command: String, workingDirectory: String?): AgentShellExecutor.ExecutionResult =
        coroutineScope {
            var allocated: Process? = null
            try {
                val process = withContext(Dispatchers.IO) {
                    ProcessBuilder(commandLine(command))
                        .directory(workingDirectory?.let { java.io.File(it) })
                        .redirectErrorStream(true)
                        .start().also { allocated = it }
                }
                val output = async(Dispatchers.IO) {
                    try { process.inputStream.use { it.readBytes().decodeToString() } }
                    catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
                }
                val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
                AgentShellExecutor.ExecutionResult(output.await(), exitCode)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    allocated?.let(::closeDesktopShellProcess)
                }
            }
        }

    private fun commandLine(command: String): List<String> =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("cmd.exe", "/d", "/s", "/c", "\"$command\"")
        } else {
            listOf("/bin/sh", "-c", command)
        }
}

/** Terminate the foreground process tree and join exit before its output reader is released. */
internal fun closeDesktopShellProcess(process: Process) {
    val descendants = process.descendants().use { it.toList() }
    descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
    if (process.isAlive) process.destroyForcibly()
    check(process.waitFor(5, TimeUnit.SECONDS)) { "Shell process did not exit during cleanup" }
    descendants.forEach { child ->
        if (child.isAlive) child.onExit().get(5, TimeUnit.SECONDS)
    }
    process.inputStream.close()
    process.outputStream.close()
    process.errorStream.close()
}
