package app.kcode.tools

import app.kcode.tools.io.normalizeWorkspacePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentWorkspacePathTest {
    @Test
    fun normalizesWorkspacePaths() {
        assertEquals("", normalizeWorkspacePath("/workspace", allowRoot = true))
        assertEquals("site/index.html",
            normalizeWorkspacePath("/workspace/site/index.html", allowRoot = false)
        )
    }

    @Test
    fun rejectsTraversalAndExternalPaths() {
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkspacePath("/workspace/../secret", allowRoot = false)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkspacePath("/tmp/file", allowRoot = false)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkspacePath("/workspace", allowRoot = false)
        }
    }
}
