package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.AgentShellExecutor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.test.assertFalse

class DesktopShellCommandExecutorTest {
    @Test
    fun cancellationTerminatesTheNativeShellAndItsChildBeforeReturning() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell-cancel")
        val source = workspace.resolve("ShellFixture.java")
        val ready = workspace.resolve("ready")
        Files.writeString(source, """
            class ShellFixture {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("ready"),
                        Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(60000);
                }
            }
        """.trimIndent())
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        val invocation = if (windows) "\"$java\" \"$source\"" else "'$java' '$source'"
        val running = async(Dispatchers.Default) { DesktopShellCommandExecutor(workspace).execute(invocation, "/workspace") }
        var child: ProcessHandle? = null
        try {
            withTimeout(15_000) { while (!Files.exists(ready) || Files.size(ready) == 0L) {
                check(!running.isCompleted) { "Fixture exited before ready: ${running.await().output}" }
                delay(20)
            } }
            child = ProcessHandle.of(Files.readString(ready).trim().toLong()).orElseThrow()
            withTimeout(5_000) { running.cancelAndJoin() }
            assertFalse(child.isAlive)
        } finally {
            child?.takeIf { it.isAlive }?.destroyForcibly()
            withTimeout(5_000) { running.cancelAndJoin() }
            Files.deleteIfExists(ready)
            Files.deleteIfExists(source)
            Files.delete(workspace)
        }
    }

    @Test
    fun executesCommandsThroughKoogJvmExecutor() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell")
        val command = if (System.getProperty("os.name").lowercase().contains("win")) "cd" else "pwd"

        val result = DesktopShellCommandExecutor(workspace).execute(command, "/workspace")

        assertEquals(0, result.exitCode)
        assertContains(result.output.lowercase(), workspace.toRealPath().toString().lowercase())
    }

    @Test
    fun mapsVirtualWorkingDirectory() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell")
        val project = Files.createDirectories(workspace.resolve("project"))
        var invocation: Pair<String, String?>? = null
        val executor = DesktopShellCommandExecutor(
            workspace = workspace,
            delegate = object : AgentShellExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                ): AgentShellExecutor.ExecutionResult {
                    invocation = command to workingDirectory
                    return AgentShellExecutor.ExecutionResult("done", 0)
                }
            },
        )

        val result = executor.execute("  pwd  ", "/workspace/project")

        assertEquals("pwd" to project.toRealPath().toString(), invocation)
        assertEquals(AgentShellExecutor.ExecutionResult("done", 0), result)
    }

    @Test
    fun rejectsWorkingDirectoriesOutsideTheWorkspace(): Unit = runBlocking {
        val workspace = Files.createTempDirectory("kcode-desktop-shell")
        val executor = DesktopShellCommandExecutor(
            workspace = workspace,
            delegate = object : AgentShellExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                ) = AgentShellExecutor.ExecutionResult("unexpected", 0)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            executor.execute("pwd", "/tmp")
        }
        Unit
    }
}
