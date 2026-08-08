package app.kcode.h5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class H5WorkspaceTest {
    @Test
    fun acceptsNestedEntryInsideVirtualWorkspace() {
        assertEquals("demo/site/index.html", H5VirtualPath.relativeEntry("/workspace/demo/site/index.html"))
    }

    @Test
    fun rejectsTraversalAndPathsOutsideWorkspace() {
        assertThrows(IllegalArgumentException::class.java) {
            H5VirtualPath.relativeEntry("/workspace/demo/../secret.html")
        }
        assertThrows(IllegalArgumentException::class.java) {
            H5VirtualPath.relativeEntry("/sdcard/index.html")
        }
    }
}
