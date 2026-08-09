package ai.meteor.kcode

import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.BraveModeConfirmationHandler
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.ShellCommandExecutor
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
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
                workingDirectory = "/sdcard/Download",
            ),
        )

        assertContains(encoded, "ping -c 1 baidu.com")
        assertContains(encoded, "timeoutSeconds")
    }

    @Test
    fun koogToolsReadWriteEditAndListAtRealAbsolutePaths() = runBlocking {
        val physicalRoot = Files.createTempDirectory("kcode-agent-files")
        val fileSystem = AndroidAgentFileSystem()
        val notesDirectory = physicalRoot.resolve("notes")
        val notesFile = notesDirectory.resolve("today.md")

        WriteFileTool(fileSystem).execute(
            WriteFileTool.Args(notesFile.toString(), "first line\nsecond line")
        )
        val read = ReadFileTool(fileSystem).execute(ReadFileTool.Args(notesFile.toString()))
        val content = read.file.content as FileSystemEntry.File.Content.Text
        assertContains(content.text, "second line")

        EditFileTool(fileSystem).execute(
            EditFileTool.Args(notesFile.toString(), "second line", "updated line")
        )
        assertEquals(
            "first line\nupdated line",
            Files.readAllBytes(notesFile).decodeToString(),
        )

        val listing = ListDirectoryTool(fileSystem).execute(ListDirectoryTool.Args(physicalRoot.toString(), depth = 3))
        assertContains(listing.root.toString(), "today.md")
    }

    @Test
    fun mediaToolReturnsPngAsNativeImageAttachment() = runBlocking {
        val physicalRoot = Files.createTempDirectory("kcode-agent-files")
        val fileSystem = AndroidAgentFileSystem()
        val imageFile = physicalRoot.resolve("images/sample.png")
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0xff.toByte(), 0x80.toByte(), 0x01,
        )
        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(imageFile.toString()), bytes)
        val read = ReadMediaFileTool(fileSystem).execute(
            ReadMediaFileTool.Args(imageFile.toString()),
        )
        val attachment = ReadMediaFileTool(fileSystem)
            .encodeResultToParts(read, KotlinxSerializer())
            .filterIsInstance<MessagePart.Attachment>()
            .single()
        val source = attachment.source as AttachmentSource.Image
        val attachedBytes = (source.content as AttachmentContent.Binary.Bytes).data

        assertTrue(bytes.contentEquals(Files.readAllBytes(imageFile)))
        assertEquals(bytes.size.toLong(), read.size)
        assertEquals(ReadMediaFileTool.MediaType.Image, read.mediaType)
        assertEquals("image/png", source.mimeType)
        assertTrue(bytes.contentEquals(attachedBytes))
    }

    @Test
    fun mediaToolReturnsMp4AsNativeVideoAttachment() = runBlocking {
        val fileSystem = AndroidAgentFileSystem()
        val videoFile = Files.createTempDirectory("kcode-agent-files").resolve("sample.mp4")
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6f, 0x6d, 0x00, 0x00, 0x00, 0x00,
        )
        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(videoFile.toString()), bytes)
        val tool = ReadMediaFileTool(fileSystem)

        val result = tool.execute(ReadMediaFileTool.Args(videoFile.toString()))
        val attachment = tool.encodeResultToParts(result, KotlinxSerializer())
            .filterIsInstance<MessagePart.Attachment>()
            .single()

        assertEquals(ReadMediaFileTool.MediaType.Video, result.mediaType)
        assertEquals("video/mp4", (attachment.source as AttachmentSource.Video).mimeType)
    }

    @Test
    fun acceptsAnyRealAbsolutePathAllowedByTheOperatingSystem() = runBlocking {
        val fileSystem = AndroidAgentFileSystem()
        val firstRoot = Files.createTempDirectory("kcode-agent-files-first")
        val secondRoot = Files.createTempDirectory("kcode-agent-files-second")
        val firstFile = firstRoot.resolve("first.txt")
        val secondFile = secondRoot.resolve("nested/second.txt")

        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(firstFile.toString()), "first".encodeToByteArray())
        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(secondFile.toString()), "second".encodeToByteArray())

        assertEquals("first", Files.readAllBytes(firstFile).decodeToString())
        assertEquals("second", Files.readAllBytes(secondFile).decodeToString())
        assertFailsWith<IllegalArgumentException> { fileSystem.fromAbsolutePathString("relative/file.txt") }
        Unit
    }

    @Test
    fun mapsVirtualWorkspaceToWebContainerWorkspace() = runBlocking {
        val physicalRoot = Files.createTempDirectory("kcode-web-workspace")
        val fileSystem = AndroidAgentFileSystem(physicalRoot)
        val virtual = "/workspace/demo/index.html"

        fileSystem.writeBytes(fileSystem.fromAbsolutePathString(virtual), "<html></html>".encodeToByteArray())

        assertEquals("<html></html>", Files.readAllBytes(physicalRoot.resolve("demo/index.html")).decodeToString())
        assertEquals(virtual, fileSystem.toAbsolutePathString(physicalRoot.resolve("demo/index.html")))
        assertFailsWith<IllegalArgumentException> {
            fileSystem.fromAbsolutePathString("/workspace/../escape.html")
        }
        Unit
    }

    @Test
    fun androidShellAcceptsAndNormalizesAbsoluteWorkingDirectories() {
        val request = normalizeAndroidShellCommandRequest(
            command = "  echo delegated  ",
            workingDirectory = "/sdcard/Download/../Documents",
            timeoutSeconds = 99,
        )

        assertEquals("echo delegated", request.command)
        assertEquals("/sdcard/Documents", request.workingDirectory)
        assertEquals(20, request.timeoutSeconds)
        assertFailsWith<IllegalArgumentException> {
            normalizeAndroidShellCommandRequest("pwd", "relative/path", 10)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeAndroidShellCommandRequest("pwd", "C:\\Windows", 10)
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

        val result = tool.execute(ExecuteShellCommandTool.Args("echo delegated", 10, "/sdcard/Download"))

        assertEquals("echo delegated in /sdcard/Download", result.output)
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
            """{"command":"echo raw","timeoutSeconds":10,"workingDirectory":"/sdcard/Download"}""",
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
