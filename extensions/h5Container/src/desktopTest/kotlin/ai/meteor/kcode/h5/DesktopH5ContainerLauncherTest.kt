package ai.meteor.kcode.h5

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

class DesktopH5ContainerLauncherTest {
    @Test
    fun managesAndCapturesAChromiumPreviewWhenAvailable() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-h5-test-")
        try {
            Files.writeString(
                workspace.resolve("index.html"),
                """
                    <!doctype html><html><body style="background:#123456">
                    <h1>preview</h1><button onclick="console.log('desktop-clicked')">Run</button>
                    </body></html>
                """.trimIndent(),
            )
            val controller = DesktopH5ContainerLauncher(workspace)
            val preview = controller.launch(H5PreviewRequest("/workspace/index.html", "Test preview"))

            assertEquals(preview.containerId, controller.list().single().id)
            if (preview.presentation == "desktop-managed-chromium") {
                controller.interact(
                    H5InteractionRequest(
                        preview.containerId,
                        H5InteractionAction.Click,
                        selector = "[aria-label='Move preview to background']",
                    ),
                )
                repeat(50) {
                    if (controller.list().single().state == H5ContainerState.Background) return@repeat
                    delay(20)
                }
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
                val button = controller.inspect(preview.containerId).elements.single { it.name == "Run" }
                controller.interact(
                    H5InteractionRequest(
                        preview.containerId,
                        H5InteractionAction.Click,
                        handle = button.handle,
                    ),
                )
                val console = controller.console(preview.containerId, cursor = 0, limit = 100)
                assertContains(console.entries.map { it.message }, "desktop-clicked")
            }

            controller.close(preview.containerId)
            assertTrue(controller.list().isEmpty())
        } finally {
            deleteTemporaryDirectory(workspace)
        }
    }

    private fun deleteTemporaryDirectory(directory: Path) {
        val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val target = directory.toAbsolutePath().normalize()
        require(target.startsWith(temporaryRoot) && target.fileName.toString().startsWith("kcode-h5-test-"))
        if (!Files.exists(target)) return
        Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
