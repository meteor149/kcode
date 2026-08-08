package app.kcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.browser.localStorage
import kotlinx.coroutines.test.runTest
import app.kcode.settings.WebAppSettingsStore

class WebAgentWorkspaceTest {
    @Test
    fun createsLocalKoogAgentService() {
        val service = createWebKoogChatService(WebAppSettingsStore, WebToolPermissionState())

        assertNull(service.availability)
    }

    @Test
    fun persistsAndListsWorkspaceFiles() = runTest {
        val workspace = WebAgentWorkspace()
        val path = "/workspace/test-${kotlin.random.Random.nextInt()}/index.html"
        val key = "kcode.workspace.file.${path.removePrefix("/workspace/")}"
        try {
            workspace.writeText(path, "<h1>kcode</h1>")

            assertEquals("<h1>kcode</h1>", workspace.readText(path))
            val rootEntries = workspace.list("/workspace")
            assertTrue(rootEntries.any { it.directory && path.startsWith(it.path + "/") })
        } finally {
            localStorage.removeItem(key)
        }
    }
}
