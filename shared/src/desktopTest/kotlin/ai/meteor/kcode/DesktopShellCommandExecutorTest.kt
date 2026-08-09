package ai.meteor.kcode

import ai.koog.agents.ext.tool.shell.ShellCommandExecutor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class DesktopShellCommandExecutorTest {
    @Test
    fun executesCommandsThroughKoogJvmExecutor() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell")
        val command = if (System.getProperty("os.name").lowercase().contains("win")) "cd" else "pwd"

        val result = DesktopShellCommandExecutor(workspace).execute(command, "/workspace", 10)

        assertEquals(0, result.exitCode)
        assertContains(result.output.lowercase(), workspace.toRealPath().toString().lowercase())
    }

    @Test
    fun mapsVirtualWorkingDirectoryAndClampsTimeout() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell")
        val project = Files.createDirectories(workspace.resolve("project"))
        var invocation: Triple<String, String?, Int>? = null
        val executor = DesktopShellCommandExecutor(
            workspace = workspace,
            delegate = object : ShellCommandExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                    timeoutSeconds: Int,
                ): ShellCommandExecutor.ExecutionResult {
                    invocation = Triple(command, workingDirectory, timeoutSeconds)
                    return ShellCommandExecutor.ExecutionResult("done", 0)
                }
            },
        )

        val result = executor.execute("  pwd  ", "/workspace/project", 60)

        assertEquals(Triple("pwd", project.toRealPath().toString(), 20), invocation)
        assertEquals(ShellCommandExecutor.ExecutionResult("done", 0), result)
    }

    @Test
    fun rejectsWorkingDirectoriesOutsideTheWorkspace(): Unit = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell")
        val executor = DesktopShellCommandExecutor(
            workspace = workspace,
            delegate = object : ShellCommandExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                    timeoutSeconds: Int,
                ) = ShellCommandExecutor.ExecutionResult("unexpected", 0)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            executor.execute("pwd", "/tmp", 10)
        }
        Unit
    }
}
