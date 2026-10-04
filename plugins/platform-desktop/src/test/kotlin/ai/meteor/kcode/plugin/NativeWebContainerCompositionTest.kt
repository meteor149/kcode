package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.webcontainer.native.DesktopNativeWebContainerPlugin
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin

class NativeWebContainerCompositionTest {
    @Test
    fun actualJarMountsOwnIndependentBrowsersAndServersAcrossWithdrawal(): Unit = runBlocking {
        val directory = Files.createTempDirectory("native-web-jar").toFile()
        val artifact = File(directory, "web.jar")
        File(DesktopNativeWebContainerPlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        val workspace = File(directory, "workspace").apply { mkdirs() }
        File(workspace, "index.html").writeText("<!doctype html><html><body><button onclick=\"console.log('owned-click')\">Owned content</button></body></html>")
        var first: WebContainerController? = null
        var second: WebContainerController? = null
        fun capture(id: String, bind: (WebContainerController) -> Unit) = kcodePlugin(
            PluginDescriptor(id, "test", "test", emptySet()),
            plugin<Unit>(name = id, inject = dependencies(KcodeWebContainers.Key)) { ctx, _ ->
                ctx.require(KcodeWebContainers.Key).controller?.let(bind)
            }, Unit,
        )
        suspend fun create(capture: KcodePluginMount) = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val a = create(capture("test.web-a") { first = it })
        val b = create(capture("test.web-b") { second = it })
        val client = HttpClient.newHttpClient()
        fun request(url: String) = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(3)).build()
        val spec = DynamicPluginSpec(
            id = "provider.web-containers.platform", version = "jar", entryClass = DesktopNativeWebContainerPlugin::class.java.name,
            artifactPath = artifact.path, sha256 = digest, config = workspace.absolutePath,
        )
        try {
            a.pluginManager.replace(spec)
            b.pluginManager.replace(spec)
            val original = requireNotNull(first)
            val other = requireNotNull(second)
            assertNotSame(DesktopNativeWebContainerPlugin::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(original.javaClass.classLoader, other.javaClass.classLoader)
            val openedA = original.launch(WebPreviewRequest("/workspace/index.html", "Owned A"))
            val openedB = other.launch(WebPreviewRequest("/workspace/index.html", "Owned B"))
            // This fixture requires installed Chromium; external browser fallback cannot prove ownership.
            assertEquals("desktop-managed-chromium", openedA.presentation)
            assertEquals("desktop-managed-chromium", openedB.presentation)
            assertEquals(listOf(openedA.containerId), original.list().map { it.id })
            assertEquals(listOf(openedB.containerId), other.list().map { it.id })
            val resourcesA = resources(original)
            val resourcesB = resources(other)
            assertTrue(Files.isDirectory(resourcesA.profile))
            assertTrue(Files.isDirectory(resourcesB.profile))
            val page = original.inspect(openedA.containerId)
            val button = page.elements.single { it.name == "Owned content" }
            original.interact(WebInteractionRequest(openedA.containerId, WebInteractionAction.Click, handle = button.handle))
            assertTrue(original.console(openedA.containerId, 0, 100).entries.any { it.message == "owned-click" })
            original.interact(WebInteractionRequest(openedA.containerId, WebInteractionAction.Click, selector = button.selector))
            assertEquals(2, original.console(openedA.containerId, 0, 100).entries.count { it.message == "owned-click" })
            assertEquals(200, client.send(request(page.url), HttpResponse.BodyHandlers.ofString()).statusCode())
            a.pluginManager.setEnabled("provider.web-containers.platform", false)
            assertFailsWith<IllegalStateException> { original.list() }
            assertTrue(!resourcesA.process.isAlive)
            assertTrue(!Files.exists(resourcesA.profile))
            assertTrue(resourcesA.client.isTerminated)
            assertTrue(Files.isDirectory(resourcesB.profile))
            assertTrue(!resourcesB.client.isTerminated)
            assertFailsWith<java.io.IOException> { client.send(request(page.url), HttpResponse.BodyHandlers.ofString()) }
            assertEquals(openedB.containerId, other.list().single().id)
            assertTrue(other.inspect(openedB.containerId).elements.any { it.name == "Owned content" })
            a.pluginManager.setEnabled("provider.web-containers.platform", true)
            val restored = requireNotNull(first)
            assertNotSame(original, restored)
            assertTrue(restored.list().isEmpty())
            restored.launch(WebPreviewRequest("/workspace/index.html", "Restored A"))
            a.pluginManager.uninstall("provider.web-containers.platform")
            assertFailsWith<IllegalStateException> { restored.list() }
            assertEquals(openedB.containerId, other.list().single().id)
        } finally {
            try { a.close() } finally {
                try { b.close() } finally {
                    client.close()
                    directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
                }
            }
        }
    }
    private data class NativeResources(val process: Process, val profile: Path, val client: HttpClient)

    // Observe actual OS resources from the isolated module, without adding test hooks to product APIs.
    private fun resources(controller: WebContainerController): NativeResources {
        fun read(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)
        val launcher = read(controller, "delegate")
        assertEquals(controller.javaClass.classLoader, launcher.javaClass.classLoader)
        val session = read(read(launcher, "sessions"), "active")
        val browser = read(session, "browser")
        return NativeResources(read(browser, "process") as Process, read(browser, "userDataDirectory") as Path,
            read(browser, "httpClient") as HttpClient)
    }
}
