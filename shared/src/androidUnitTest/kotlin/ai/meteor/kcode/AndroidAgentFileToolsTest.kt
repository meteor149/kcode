package ai.meteor.kcode

import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.rag.base.files.model.FileSystemEntry
import ai.koog.serialization.kotlinx.KotlinxSerializer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidAgentFileToolsTest {
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
        assertTrue("second line" in content.text)

        EditFileTool(fileSystem).execute(
            EditFileTool.Args(notesFile.toString(), "second line", "updated line")
        )
        assertEquals(
            "first line\nupdated line",
            Files.readAllBytes(notesFile).decodeToString(),
        )

        val listing = ListDirectoryTool(fileSystem).execute(ListDirectoryTool.Args(physicalRoot.toString(), depth = 3))
        assertTrue("today.md" in listing.root.toString())
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
        )

        assertEquals("echo delegated", request.command)
        assertEquals("/sdcard/Documents", request.workingDirectory)
        assertFailsWith<IllegalArgumentException> {
            normalizeAndroidShellCommandRequest("pwd", "relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeAndroidShellCommandRequest("pwd", "C:\\Windows")
        }
    }

    @Test
    fun ubuntuShellNormalizesLinuxPathsAndAllowsLongScripts() {
        val request = normalizeUbuntuShellCommandRequest(
            command = "  python3 - <<'PY'\nprint('ok')\nPY  ",
            workingDirectory = "/workspace/project/../demo",
        )

        assertEquals("python3 - <<'PY'\nprint('ok')\nPY", request.command)
        assertEquals("/workspace/demo", request.workingDirectory)
        assertEquals("/workspace", normalizeUbuntuShellCommandRequest("pwd", null).workingDirectory)
        assertFailsWith<IllegalArgumentException> {
            normalizeUbuntuShellCommandRequest("pwd", "relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeUbuntuShellCommandRequest("pwd", "C:\\Windows")
        }
    }

    @Test
    fun ubuntuProotCommandUsesIsolatedGuestEnvironmentAndWorkspaceBind() {
        val runtime = UbuntuRuntimePaths(
            runtimeDirectory = Path.of("runtime"),
            rootFileSystem = Path.of("rootfs"),
            temporaryDirectory = Path.of("tmp"),
            prootExecutable = Path.of("proot"),
            loaderExecutable = Path.of("loader"),
        )
        val command = buildUbuntuProotCommand(
            runtime = runtime,
            request = UbuntuShellCommandRequest("printf '%s' \"\$PATH\"", "/workspace"),
            bindMounts = listOf(UbuntuBindMount(Path.of("workspace"), "/workspace")),
        )

        assertEquals("proot", command.first())
        assertEquals("-0", command[1])
        assertTrue("workspace:/workspace" in command)
        assertTrue("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" in command)
        assertEquals("printf '%s' \"\$PATH\"", command.last())
        assertFalse(command.any { "system/bin" in it })
    }

    @Test
    fun rootfsArchivePathsCannotEscapeAtomicInstallDirectory() {
        assertEquals(
            Path.of("usr/bin/python3"),
            validatedRootfsRelativePath("ubuntu-noble-aarch64/usr/bin/python3"),
        )
        assertEquals(null, validatedRootfsRelativePath("ubuntu-noble-aarch64/"))
        assertFailsWith<IllegalArgumentException> {
            validatedRootfsRelativePath("ubuntu-noble-aarch64/../../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            validatedRootfsRelativePath("different-root/etc/passwd")
        }
    }
}
