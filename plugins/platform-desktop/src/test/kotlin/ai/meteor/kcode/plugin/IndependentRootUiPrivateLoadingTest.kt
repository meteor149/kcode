package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class IndependentRootUiPrivateLoadingTest {
    @Test
    fun privateRootRendersAndRetiresWithoutAnyDefaultApplicationProvider(): Unit = runBlocking {
        val directory = Files.createTempDirectory("independent-root")
        val artifact = directory.resolve("root.jar")
        val classes = Path.of(FreeformRootUiPlugin::class.java.protectionDomain.codeSource.location.toURI())
        JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
            Files.list(classes.resolve("ai/meteor/kcode/plugin")).use { files ->
                files.filter { it.fileName.toString().contains("FreeformRootUi") }.forEach { file ->
                    jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, jar)
                    jar.closeEntry()
                }
            }
        }
        check(artifact.toFile().setReadOnly())
        lateinit var root: ApplicationRenderer
        val capture = kcodePlugin(PluginDescriptor("capture.root", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeApplicationUi.Key)) { ctx, _ ->
                root = ctx.require(KcodeApplicationUi.Key).renderer
            }, Unit)
        val compositionStore = FilePluginCompositionStore(directory.toFile())
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { false }),
            profile = KcodePluginProfile(includeDefaults = false),
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("test.installations", "test", "test", emptySet()),
                PluginInstallationsProviderPlugin, compositionStore,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory.toFile())
            },
        ))
        try {
            val deployment = DynamicPluginSpec(
                id = "independent.root", version = "test", artifactPath = artifact.toString(),
                entryClass = FreeformRootUiPlugin::class.java.name,
                sha256 = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
                    .joinToString("") { "%02x".format(it) },
            )
            runtime.pluginManager.install(deployment)
            var original = root
            assertNotSame(ApplicationRenderer::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(FreeformRootUiPlugin::class.java.classLoader, original.javaClass.classLoader)
            val lookup = field(original, "previousLookup") as ApplicationServices
            assertFailsWith<IllegalStateException> { lookup[KcodeSettings.Key] }
            assertEquals(setOf("core.plugin-inventory", "core.loader", "capture.root", "independent.root", "test.installations"), runtime.diagnostics().plugins.map { it.id }.toSet())
            withContext(Dispatchers.Main.immediate) {
                val scene = ImageComposeScene(width = 480, height = 240, coroutineContext = coroutineContext) {
                    runtime.Render(ApplicationHostOptions())
                }
                suspend fun frames() { repeat(6) { scene.render(System.nanoTime()).close(); delay(20) } }
                try {
                    frames()
                    assertTrue((field(original, "rendered") as AtomicInteger).get() > 0)
                    assertTrue("Independent root without sidebar or application features" in semanticsLabels(scene))
                    val prior = original
                    assertFailsWith<IllegalStateException> {
                        runtime.pluginManager.replace(deployment.copy(version = "broken", entryClass = FreeformRootUiBrokenPlugin::class.java.name))
                    }
                    assertEquals("test", compositionStore.load().external.single().version)
                    assertNotSame(prior, root)
                    original = root
                    frames()
                    assertEquals(1, (field(prior, "disposed") as AtomicInteger).get())
                    val rendered = field(original, "rendered") as AtomicInteger
                    val disposed = field(original, "disposed") as AtomicInteger
                    assertTrue(rendered.get() > 0)
                    runtime.pluginManager.setEnabled("independent.root", false)
                    frames()
                    assertEquals(1, disposed.get())
                    val finalRenders = rendered.get()
                    frames()
                    assertEquals(finalRenders, rendered.get())
                    runtime.pluginManager.setEnabled("independent.root", true)
                    frames()
                    assertNotSame(original, root)
                    assertTrue((field(root, "rendered") as AtomicInteger).get() > 0)
                    val replacement = root
                    runtime.pluginManager.uninstall("independent.root")
                    frames()
                    assertEquals(1, (field(replacement, "disposed") as AtomicInteger).get())
                } finally { scene.close() }
            }
        } finally {
            runtime.close()
            directory.toFile().walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private fun field(value: Any, name: String): Any = value.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        requireNotNull(it.get(value))
    }
    private fun semanticsLabels(imageScene: ImageComposeScene): List<String> {
        fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }
        val scene = field(imageScene, "scene")
        val root = field(scene, "mainOwner")
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        val labels = mutableListOf<String>()
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
            labels += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            labels += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            node.children.forEach(::collect)
        }
        collect(owner.unmergedRootSemanticsNode)
        return labels
    }
}
