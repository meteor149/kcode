package ai.meteor.kcode.plugin.nativefilesystem


import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.rag.base.files.model.FileSystemEntry
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
    fun mapsVirtualWorkspaceToManagedWorkspace() = runBlocking {
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

}
