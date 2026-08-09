package ai.meteor.kcode

import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.BraveModeConfirmationHandler
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.ShellCommandExecutor
import ai.koog.rag.base.files.model.FileSystemEntry
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class AndroidAgentFileToolsTest {
    @Test
    fun shellArgumentsHaveAGeneratedRuntimeSerializer() {
        val encoded = Json.encodeToString(
            ExecuteShellCommandTool.Args.serializer(),
            ExecuteShellCommandTool.Args(
                command = "ping -c 1 baidu.com",
                timeoutSeconds = 4,
                workingDirectory = "/workspace",
            ),
        )

        assertContains(encoded, "ping -c 1 baidu.com")
        assertContains(encoded, "timeoutSeconds")
    }

    @Test
    fun koogToolsReadWriteEditAndListInsideVirtualWorkspace() = runBlocking {
        val physicalRoot = Files.createTempDirectory("kcode-agent-workspace")
        val fileSystem = AndroidAgentWorkspaceFileSystem(physicalRoot)

        WriteFileTool(fileSystem).execute(
            WriteFileTool.Args("/workspace/notes/today.md", "first line\nsecond line")
        )
        val read = ReadFileTool(fileSystem).execute(ReadFileTool.Args("/workspace/notes/today.md"))
        val content = read.file.content as FileSystemEntry.File.Content.Text
        assertContains(content.text, "second line")

        EditFileTool(fileSystem).execute(
            EditFileTool.Args("/workspace/notes/today.md", "second line", "updated line")
        )
        assertEquals(
            "first line\nupdated line",
            Files.readAllBytes(physicalRoot.resolve("notes/today.md")).decodeToString(),
        )

        val listing = ListDirectoryTool(fileSystem).execute(ListDirectoryTool.Args("/workspace", depth = 3))
        assertContains(listing.root.toString(), "today.md")
    }

    @Test
    fun rejectsPathsOutsideWorkspaceAndOversizedWrites() = runBlocking {
        val fileSystem = AndroidAgentWorkspaceFileSystem(Files.createTempDirectory("kcode-agent-workspace"))

        assertFailsWith<IllegalArgumentException> {
            fileSystem.fromAbsolutePathString("/workspace/../outside.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            fileSystem.fromAbsolutePathString("/sdcard/Download/private.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            fileSystem.writeBytes(
                fileSystem.fromAbsolutePathString("/workspace/too-large.txt"),
                ByteArray(1_048_577),
            )
        }
        assertTrue(!fileSystem.exists(fileSystem.fromAbsolutePathString("/workspace/too-large.txt")))
    }

    @Test
    fun shellArgumentsAreNormalizedToTheVirtualWorkspace() {
        val request = normalizeShellCommandRequest(
            command = "  echo delegated  ",
            workingDirectory = "/workspace/project/src",
            timeoutSeconds = 99,
        )

        assertEquals("echo delegated", request.command)
        assertEquals("project/src", request.relativeWorkingDirectory)
        assertEquals(20, request.timeoutSeconds)
        assertFailsWith<IllegalArgumentException> {
            normalizeShellCommandRequest("pwd", "/sdcard", 10)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeShellCommandRequest("pwd", "/workspace/../outside", 10)
        }
    }

    @Test
    fun koogShellResultKeepsOutputAndExitCodeStructured() = runBlocking {
        val tool = ExecuteShellCommandTool(
            executor = object : ShellCommandExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                    timeoutSeconds: Int,
                ) = ShellCommandExecutor.ExecutionResult(
                    output = "$command in $workingDirectory",
                    exitCode = 7,
                )
            },
            confirmationHandler = BraveModeConfirmationHandler(),
        )

        val result = tool.execute(ExecuteShellCommandTool.Args("echo delegated", 10, "/workspace"))

        assertEquals("echo delegated in /workspace", result.output)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun standardRawShellToolCallDecodesAndExecutesWithoutNormalizer() = runBlocking {
        var executedCommand: String? = null
        val tool = ExecuteShellCommandTool(
            executor = object : ShellCommandExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                    timeoutSeconds: Int,
                ): ShellCommandExecutor.ExecutionResult {
                    executedCommand = command
                    return ShellCommandExecutor.ExecutionResult("ok", 0)
                }
            },
            confirmationHandler = BraveModeConfirmationHandler(),
        )
        val rawArgs = Json.parseToJsonElement(
            """{"command":"echo raw","timeoutSeconds":10,"workingDirectory":"/workspace"}""",
        ).jsonObject.toKoogJSONObject()

        val args = tool.decodeArgs(rawArgs, KotlinxSerializer())
        val result = tool.execute(args)

        assertEquals("echo raw", executedCommand)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun wrappedArgumentsAreRejectedByKoogWithoutNormalizer() {
        val tool = ExecuteShellCommandTool(
            executor = object : ShellCommandExecutor {
                override suspend fun execute(
                    command: String,
                    workingDirectory: String?,
                    timeoutSeconds: Int,
                ) = ShellCommandExecutor.ExecutionResult("unexpected", 0)
            },
            confirmationHandler = BraveModeConfirmationHandler(),
        )
        val wrapped = Json.parseToJsonElement(
            """{"arguments":{"command":"echo wrapped","timeoutSeconds":10}}""",
        ).jsonObject.toKoogJSONObject()

        assertFailsWith<Exception> {
            tool.decodeArgs(wrapped, KotlinxSerializer())
        }
    }
}
