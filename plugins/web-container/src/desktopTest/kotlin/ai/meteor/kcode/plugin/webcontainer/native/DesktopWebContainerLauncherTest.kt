package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebPreviewRequest

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

class DesktopWebContainerLauncherTest {
    @Test
    fun processTeardownWaitsForAnOwnedNativeProcessToExit() {
        val directory = Files.createTempDirectory("kcode-web-test-")
        val source = directory.resolve("PreviewProcess.java")
        Files.writeString(source, """
            class PreviewProcess {
                public static void main(String[] args) throws Exception {
                    System.out.println("ready");
                    Thread.sleep(60000);
                }
            }
        """.trimIndent())
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(java.toString(), source.toString()).redirectErrorStream(true).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            assertTrue(process.isAlive)
            terminatePreviewProcess(process)
            assertTrue(!process.isAlive)
        } finally {
            terminatePreviewProcess(process)
            deleteTemporaryDirectory(directory)
        }
    }

    @Test
    fun serverTeardownJoinsOwnedWorkersAndClosesItsListeningPort() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".encodeToByteArray()) }
        }
        server.start()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/"))
            .timeout(Duration.ofSeconds(2)).build()
        val client = HttpClient.newHttpClient()
        try {
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
            stopPreviewServer(server)
            assertTrue(executor.isTerminated)
            assertFailsWith<java.io.IOException> { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        } finally { stopPreviewServer(server); client.close() }
    }

    @Test
    fun opensAndInspectsRemoteWebsiteWhenManagedChromiumIsAvailable() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = "<!doctype html><html><head><title>Remote test</title></head><body><button>Remote action</button></body></html>"
                .encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val workspace = Files.createTempDirectory("kcode-web-test-")
        val controller = DesktopWebContainerLauncher(workspace)
        val url = "http://127.0.0.1:${server.address.port}/"
        try {
            val preview = controller.launch(WebPreviewRequest(url, "Remote preview"))
            assertEquals(url, controller.list().single().entryPath)
            if (preview.presentation == "desktop-managed-chromium") {
                val page = controller.inspect(preview.containerId)
                assertEquals("Remote test", page.title)
                assertTrue(page.elements.any { it.name == "Remote action" })
                assertTrue(controller.screenshot(preview.containerId).pngBytes.size > 100)
            }
            controller.closeAll()
            controller.closeAll()
            assertTrue(controller.list().isEmpty())
        } finally {
            try { controller.closeAll() } finally { server.stop(0); deleteTemporaryDirectory(workspace) }
        }
    }

    @Test
    fun managesAndCapturesAChromiumPreviewWhenAvailable() = runBlocking {
        val workspace = Files.createTempDirectory("kcode-web-test-")
        val controller = DesktopWebContainerLauncher(workspace)
        try {
            Files.writeString(
                workspace.resolve("index.html"),
                """
                    <!doctype html><html><body style="background:#123456">
                    <h1>preview</h1><button onclick="console.log('desktop-clicked')">Run</button>
                    </body></html>
                """.trimIndent(),
            )
            val preview = controller.launch(WebPreviewRequest("/workspace/index.html", "Test preview"))

            assertEquals(preview.containerId, controller.list().single().id)
            if (preview.presentation == "desktop-managed-chromium") {
                controller.interact(
                    WebInteractionRequest(
                        preview.containerId,
                        WebInteractionAction.Click,
                        selector = "[aria-label='Move preview to background']",
                    ),
                )
                repeat(50) {
                    if (controller.list().single().state == WebContainerState.Background) return@repeat
                    delay(20)
                }
                assertEquals(
                    WebContainerState.Background,
                    controller.list().single().state,
                )
                assertEquals(
                    WebContainerState.Foreground,
                    controller.setState(preview.containerId, WebContainerState.Foreground).state,
                )
                val screenshot = controller.screenshot(preview.containerId)
                assertTrue(screenshot.pngBytes.size > 100)
                assertTrue(screenshot.width > 0)
                assertTrue(screenshot.height > 0)
                val button = controller.inspect(preview.containerId).elements.single { it.name == "Run" }
                controller.interact(
                    WebInteractionRequest(
                        preview.containerId,
                        WebInteractionAction.Click,
                        handle = button.handle,
                    ),
                )
                val console = controller.console(preview.containerId, cursor = 0, limit = 100)
                assertContains(console.entries.map { it.message }, "desktop-clicked")
            }

            controller.closeAll()
            controller.closeAll()
            assertTrue(controller.list().isEmpty())
            assertTrue(controller.list().isEmpty())
        } finally {
            try { controller.closeAll() } finally { deleteTemporaryDirectory(workspace) }
        }
    }

    private fun deleteTemporaryDirectory(directory: Path) {
        val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val target = directory.toAbsolutePath().normalize()
        require(target.startsWith(temporaryRoot) && target.fileName.toString().startsWith("kcode-web-test-"))
        if (!Files.exists(target)) return
        Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
