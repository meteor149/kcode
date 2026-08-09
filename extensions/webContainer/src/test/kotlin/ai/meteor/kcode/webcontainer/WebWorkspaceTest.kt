package ai.meteor.kcode.webcontainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebWorkspaceTest {
    @Test
    fun acceptsNestedEntryInsideVirtualWorkspace() {
        assertEquals("demo/site/index.html", WebVirtualPath.relativeEntry("/workspace/demo/site/index.html"))
    }

    @Test
    fun rejectsTraversalAndPathsOutsideWorkspace() {
        assertThrows(IllegalArgumentException::class.java) {
            WebVirtualPath.relativeEntry("/workspace/demo/../secret.html")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebVirtualPath.relativeEntry("/sdcard/index.html")
        }
    }
}
