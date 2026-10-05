package ai.meteor.kcode.plugin

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.api.AndroidPluginWindowContent
import ai.meteor.kcode.plugin.api.AndroidPluginWindowFactory
import ai.meteor.kcode.plugin.api.AndroidPluginWindow
import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.ApplicationServices
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.NativeAndroidPluginWindowHost
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.ComposeView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.cordis.packages.packageFileSha256
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class AndroidIndependentRootUiPrivateLoadingTest {
    @Test(timeout = 90_000)
    fun privateRootRendersAndRetiresWithoutAnyDefaultApplicationProvider(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "independent-root-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "root.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var root: ApplicationRenderer
        val capture = kcodePlugin(PluginDescriptor("capture.root", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeApplicationUi.Key)) { ctx, _ ->
                root = ctx.require(KcodeApplicationUi.Key).renderer
            }, Unit)
        val compositionStore = FilePluginCompositionStore(directory)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { false }),
            profile = KcodePluginProfile(includeDefaults = false),
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("test.installations", "test", "test", emptySet()),
                PluginInstallationsProviderPlugin, compositionStore,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        var window: AndroidPluginWindow? = null
        lateinit var view: ComposeView
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            val deployment = DynamicPluginSpec(
                id = "independent.root", version = "test", artifactPath = artifact.path,
                entryClass = FreeformRootUiPlugin::class.java.name,
                packageName = instrumentation.context.packageName,
                sha256 = packageFileSha256(artifact),
            )
            runtime.pluginManager.install(deployment)
            var original = root
            assertNotSame(ApplicationRenderer::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(FreeformRootUiPlugin::class.java.classLoader, original.javaClass.classLoader)
            val lookup = field(original, "previousLookup") as ApplicationServices
            assertFailsWith<IllegalStateException> { lookup[KcodeSettings.Key] }
            assertEquals(setOf("core.plugin-inventory", "core.loader", "capture.root", "independent.root", "test.installations"), runtime.diagnostics().plugins.map { it.id }.toSet())
            window = NativeAndroidPluginWindowHost(context).open(AndroidPluginWindowFactory { activity ->
                object : AndroidPluginWindowContent {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        activity.setShowWhenLocked(true)
                        activity.setTurnScreenOn(true)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view = ComposeView(activity)
                        activity.setContentView(view)
                        view.setContent { runtime.Render(ApplicationHostOptions()) }
                    }
                    override suspend fun close() {
                        withContext(Dispatchers.Main.immediate) { view.disposeComposition() }
                    }
                }
            })
            withTimeout(10_000) { window.show(); window.awaitReady() }
            withTimeout(10_000) { while ((field(original, "rendered") as AtomicInteger).get() == 0) delay(30) }
            val prior = original
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(deployment.copy(version = "broken", entryClass = FreeformRootUiBrokenPlugin::class.java.name))
            }
            assertEquals("test", compositionStore.load().external.single().version)
            assertNotSame(prior, root)
            original = root
            withTimeout(10_000) { while ((field(original, "rendered") as AtomicInteger).get() == 0 ||
                (field(prior, "disposed") as AtomicInteger).get() == 0) delay(30) }
            assertEquals(1, (field(prior, "disposed") as AtomicInteger).get())
            val rendered = field(original, "rendered") as AtomicInteger
            val disposed = field(original, "disposed") as AtomicInteger
            assertTrue(withContext(Dispatchers.Main.immediate) { view.isAttachedToWindow })
            assertTrue(withContext(Dispatchers.Main.immediate) {
                "Independent root without sidebar or application features" in semanticsLabels(view)
            })
            runtime.pluginManager.setEnabled("independent.root", false)
            withTimeout(10_000) { while (disposed.get() == 0) delay(30) }
            assertEquals(1, disposed.get())
            val finalRenders = rendered.get()
            delay(100)
            assertEquals(finalRenders, rendered.get())
            runtime.pluginManager.setEnabled("independent.root", true)
            assertNotSame(original, root)
            val replacement = root
            withTimeout(10_000) { while ((field(replacement, "rendered") as AtomicInteger).get() == 0) delay(30) }
            runtime.pluginManager.uninstall("independent.root")
            withTimeout(10_000) { while ((field(replacement, "disposed") as AtomicInteger).get() == 0) delay(30) }
            assertEquals(1, (field(replacement, "disposed") as AtomicInteger).get())
        } finally {
            try { window?.close() } finally {
                try { runtime.close() } finally {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                    directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
                }
            }
        }
    }

    private fun field(value: Any, name: String): Any = value.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        requireNotNull(it.get(value))
    }
    private fun semanticsLabels(view: View): List<String> {
        val labels = mutableListOf<String>()
        fun visit(current: View) {
            val ownerMethod = current.javaClass.methods.firstOrNull {
                it.name == "getSemanticsOwner" && it.parameterCount == 0
            }
            val owner = ownerMethod?.invoke(current) as? SemanticsOwner
            if (owner != null) {
                fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
                    labels += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                    labels += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    node.children.forEach(::collect)
                }
                collect(owner.unmergedRootSemanticsNode)
            } else if (current is ViewGroup) {
                repeat(current.childCount) { visit(current.getChildAt(it)) }
            }
        }
        visit(view)
        return labels
    }
}
