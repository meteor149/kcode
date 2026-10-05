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
import org.cordis.packages.packageFileSha256
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.desktopPackageHost
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
        val archive = File(directory, "web.kplugin")
        requireNotNull(javaClass.classLoader.getResourceAsStream("kcode/plugins/feature.web-container-1.0.0.kplugin"))
            .use { input -> archive.outputStream().use { input.copyTo(it) } }
        check(archive.setReadOnly())
        val digest = packageFileSha256(archive)
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
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(directory, desktopPackageHost()), Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val a = create(capture("test.web-a") { first = it })
        val b = create(capture("test.web-b") { second = it })
        val client = HttpClient.newHttpClient()
        fun request(url: String) = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(3)).build()
        val spec = PluginPackageImport(archive.absolutePath, digest, StoredPluginConfiguration.encode(workspace.absolutePath))
        try {
            a.pluginManager.importPackages(listOf(spec))
            b.pluginManager.importPackages(listOf(spec))
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
            a.pluginManager.setEnabled("feature.web-container", false)
            assertFailsWith<IllegalStateException> { original.list() }
            assertTrue(!resourcesA.process.isAlive)
            assertTrue(!Files.exists(resourcesA.profile))
            assertTrue(resourcesA.client.isTerminated)
            assertTrue(Files.isDirectory(resourcesB.profile))
            assertTrue(!resourcesB.client.isTerminated)
            assertFailsWith<java.io.IOException> { client.send(request(page.url), HttpResponse.BodyHandlers.ofString()) }
            assertEquals(openedB.containerId, other.list().single().id)
            assertTrue(other.inspect(openedB.containerId).elements.any { it.name == "Owned content" })
            a.pluginManager.setEnabled("feature.web-container", true)
            val restored = requireNotNull(first)
            assertNotSame(original, restored)
            assertTrue(restored.list().isEmpty())
            restored.launch(WebPreviewRequest("/workspace/index.html", "Restored A"))
            a.pluginManager.uninstall("feature.web-container")
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
