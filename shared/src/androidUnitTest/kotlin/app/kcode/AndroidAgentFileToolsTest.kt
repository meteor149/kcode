package app.kcode

import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.rag.base.files.model.FileSystemEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class AndroidAgentFileToolsTest {
    @Test
    fun shellArgumentsHaveAGeneratedRuntimeSerializer() {
        val encoded = Json.encodeToString(
            AndroidShellTool.Args.serializer(),
            AndroidShellTool.Args(command = "ping -c 1 baidu.com", timeoutSeconds = 4),
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
    fun shellToolOnlySelectsExecutionIdentityAndDelegatesPermissionToGlobalGate() = runBlocking {
        var selectedMode: app.kcode.settings.ShellExecutionMode? = null
        val tool = AndroidShellTool(
            modeProvider = { app.kcode.settings.ShellExecutionMode.Adb },
            executeCommand = { mode, command, timeout ->
                selectedMode = mode
                "$command:$timeout"
            },
        )

        val result = tool.execute(AndroidShellTool.Args("echo delegated", timeoutSeconds = 99))

        assertEquals(app.kcode.settings.ShellExecutionMode.Adb, selectedMode)
        assertEquals("echo delegated:20", result)
    }
}
