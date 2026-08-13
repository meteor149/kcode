package ai.meteor.kcode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AgentShellToolTest {
    @Test
    fun executesWithoutExposingOrApplyingTimeout() = runBlocking {
        var invocation: Pair<String, String?>? = null
        val tool = AgentShellTool(
            object : AgentShellExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                ): AgentShellExecutor.ExecutionResult {
                    invocation = command to workingDirectory
                    return AgentShellExecutor.ExecutionResult("complete output", 0)
                }
            },
        )

        val output = tool.execute(AgentShellTool.Args("build", "/workspace/project"))

        assertEquals("build" to "/workspace/project", invocation)
        assertEquals("complete output\nExit code: 0", output)
    }

    @Test
    fun preservesCompleteOutput() = runBlocking {
        val completeOutput = "x".repeat(100_000)
        val tool = AgentShellTool(
            object : AgentShellExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                ) = AgentShellExecutor.ExecutionResult(completeOutput, 0)
            },
        )

        val output = tool.execute(AgentShellTool.Args("build"))

        assertEquals(completeOutput + "\nExit code: 0", output)
    }

    @Test
    fun acceptsPlatformSpecificDescription() {
        val tool = AgentShellTool(
            executor = object : AgentShellExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                ) = AgentShellExecutor.ExecutionResult("", 0)
            },
            description = "Runs commands in an Android OS environment through /system/bin/sh.",
        )

        assertContains(tool.descriptor.description, "Android OS environment")
        assertContains(tool.descriptor.description, "/system/bin/sh")
    }

    @Test
    fun acceptsPlatformSpecificToolName() {
        val tool = AgentShellTool(
            executor = object : AgentShellExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                ) = AgentShellExecutor.ExecutionResult("", 0)
            },
            toolName = "execute_ubuntu_command",
        )

        assertEquals("execute_ubuntu_command", tool.descriptor.name)
    }
}
