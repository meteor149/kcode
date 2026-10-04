package ai.meteor.kcode.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WebPreviewSourceTest {
    @Test
    fun recognizesWorkspaceFilesAndRemoteWebsites() {
        val local = WebPreviewSource.parse("/workspace/demo/index.html")
        assertIs<WebPreviewSource.WorkspaceFile>(local)
        assertEquals("/workspace/demo/index.html", local.location)

        val remote = WebPreviewSource.parse(" https://example.com/app?q=1 ")
        assertIs<WebPreviewSource.RemoteWebsite>(remote)
        assertEquals("https://example.com/app?q=1", remote.location)
    }

    @Test
    fun rejectsUnsupportedAndMalformedRemoteLocations() {
        listOf(
            "file:///tmp/index.html",
            "javascript:alert(1)",
            "https://",
            "http://:8080/path",
            "https://example.com/has space",
        ).forEach { location ->
            assertFailsWith<IllegalArgumentException>(location) {
                WebPreviewSource.parse(location)
            }
        }
    }
}
