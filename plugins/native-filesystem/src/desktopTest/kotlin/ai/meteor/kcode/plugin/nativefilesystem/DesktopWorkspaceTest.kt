package ai.meteor.kcode.plugin.nativefilesystem

import ai.meteor.kcode.AgentWorkspaceEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class DesktopWorkspaceTest {
    @Test
    fun workspaceWritesListsAndCanonicalizesTheSameVirtualPaths() = runTest {
        val directory = Files.createTempDirectory("native-workspace")
        try {
            val workspace = DesktopAgentWorkspace(directory)
            workspace.writeText("/workspace/notes/b.txt", "bb")
            workspace.writeText("/workspace/notes/a.txt", "a")
            assertEquals("bb", workspace.readText("/workspace/notes/b.txt"))
            assertEquals(listOf(AgentWorkspaceEntry("/workspace/notes/a.txt", false, 1),
                AgentWorkspaceEntry("/workspace/notes/b.txt", false, 2)), workspace.list("/workspace/notes"))
            assertEquals("/workspace/notes/a.txt", workspace.canonicalize("/workspace/notes/a.txt"))
            val files = DesktopAgentWorkspaceFileSystem(directory)
            assertEquals("a", files.readBytes(files.fromAbsolutePathString("/workspace/notes/a.txt")).decodeToString())
            assertEquals("/workspace/notes/a.txt", files.toAbsolutePathString(directory.resolve("notes/a.txt")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun workspaceRejectsEscapesRootWritesAndOversizedContentWithoutCreatingFiles() = runTest {
        val directory = Files.createTempDirectory("native-workspace-reject")
        try {
            val workspace = DesktopAgentWorkspace(directory)
            for (path in listOf("/workspace/../escape", "/outside/file", "/workspace/bad\\name", "/workspace")) {
                assertFailsWith<IllegalArgumentException> { workspace.writeText(path, "blocked") }
            }
            assertFailsWith<IllegalArgumentException> { workspace.writeText("/workspace/large", "a".repeat(1_048_577)) }
            assertEquals(emptyList(), workspace.list("/workspace"))
            val files = DesktopAgentWorkspaceFileSystem(directory)
            assertFailsWith<IllegalArgumentException> { files.fromAbsolutePathString("/workspace/../escape") }
            assertFailsWith<IllegalArgumentException> { files.fromAbsolutePathString(directory.parent.resolve("escape").toString()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
