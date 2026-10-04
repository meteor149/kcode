package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ApplicationRenderer
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeApplicationUi
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidDefaultApplicationUiPrivateLoadingTest {
    @Test(timeout = 60000)
    fun defaultApkCreatesItsRendererWithoutHostImplementationConfiguration(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "default-ui-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "application.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var renderer: ApplicationRenderer
        val capture = kcodePlugin(PluginDescriptor("test.default-ui", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-default-ui", inject = dependencies(KcodeApplicationUi.Key)) { ctx, _ ->
                renderer = ctx.require(KcodeApplicationUi.Key).renderer
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val spec = DynamicPluginSpec(
            id = "provider.ui.compose", version = "private-default", artifactPath = artifact.path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
            entryClass = DefaultApplicationUiPlugin::class.java.name, packageName = instrumentation.context.packageName,
        )
        try {
            runtime.pluginManager.replace(spec)
            val original = renderer
            assertTrue(original.javaClass.name.startsWith("ai.meteor.kcode.plugin."))
            assertNotSame(ApplicationRenderer::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(DefaultApplicationUiPlugin::class.java.classLoader, original.javaClass.classLoader)
            // A host renderer is not a deployment configuration for the default entry.
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec.copy(version = "host-object", config = DefaultApplicationRenderer))
            }
            assertSame(original, renderer)
            runtime.pluginManager.setEnabled("provider.ui.compose", false)
            runtime.pluginManager.setEnabled("provider.ui.compose", true)
            assertNotSame(original, renderer)
            assertNotSame(ApplicationRenderer::class.java.classLoader, renderer.javaClass.classLoader)
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
