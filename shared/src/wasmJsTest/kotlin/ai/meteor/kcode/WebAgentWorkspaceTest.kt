package ai.meteor.kcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.browser.localStorage
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import ai.meteor.kcode.h5.H5PreviewRequest
import ai.meteor.kcode.h5.H5ContainerState
import ai.meteor.kcode.settings.WebAppSettingsStore

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

    @Test
    fun managesAndCapturesSandboxedH5Preview() = runTest {
        val workspace = WebAgentWorkspace()
        val path = "/workspace/h5-${kotlin.random.Random.nextInt()}/index.html"
        val key = "kcode.workspace.file.${path.removePrefix("/workspace/")}"
        val controller = WebH5ContainerLauncher(workspace)
        try {
            workspace.writeText(
                path,
                "<!doctype html><html><body><p id='value'>before</p><button onclick=\"console.log('web-clicked')\">Run</button><script>document.querySelector('#value').textContent='after'</script></body></html>",
            )
            val preview = controller.launch(H5PreviewRequest(path, "Web preview"))
            delay(100)

            assertEquals(preview.containerId, controller.list().single().id)
            val backgroundButton = document.getElementById("kcode-h5-preview")
                ?.querySelectorAll("button")
                ?.item(0) as HTMLButtonElement
            backgroundButton.click()
            assertEquals(
                H5ContainerState.Background,
                controller.list().single().state,
            )
            assertEquals(
                H5ContainerState.Foreground,
                controller.setState(preview.containerId, H5ContainerState.Foreground).state,
            )
            val screenshot = controller.screenshot(preview.containerId)
            assertTrue(screenshot.pngBytes.size > 100)
            assertTrue(screenshot.width > 0)
            assertTrue(screenshot.height > 0)
            val inspection = controller.inspect(preview.containerId)
            val button = inspection.elements.single { it.name == "Run" }
            controller.interact(
                ai.meteor.kcode.h5.H5InteractionRequest(
                    preview.containerId,
                    ai.meteor.kcode.h5.H5InteractionAction.Click,
                    handle = button.handle,
                ),
            )
            val console = controller.console(preview.containerId, cursor = 0, limit = 20)
            assertTrue(console.entries.any { it.message.contains("web-clicked") })

            controller.close(preview.containerId)
            assertTrue(controller.list().isEmpty())
        } finally {
            localStorage.removeItem(key)
        }
    }
}
