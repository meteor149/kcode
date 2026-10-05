package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidWebHostProjectionTest {
    @Test(timeout = 60_000)
    fun hostWebProjectionFollowsAnIsolatedApkAndNeverFallsBack(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "web-host-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "web-provider.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var current: WebContainerController
        val capture = kcodePlugin(PluginDescriptor("test.web", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-web", inject = dependencies(KcodeWebContainers.Key),
        ) { ctx, _ -> current = requireNotNull(ctx.require(KcodeWebContainers.Key).controller) }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            profile = KcodePluginProfile(disabled = setOf("feature.web-container")),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val host = runtime.webContainerController
        try {
            assertFails { host.list() }
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.web-host", version = "test", entryClass = AndroidFixtureHostWebController::class.java.name,
                artifactPath = apk.path,
                sha256 = packageFileSha256(apk),
                packageName = instrumentation.context.packageName, config = "APK Web provider",
            ))
            val old = current
            assertTrue(old.javaClass.classLoader !== WebContainersProviderPlugin::class.java.classLoader)
            val result = host.launch(WebPreviewRequest("/workspace/index.html", "preview"))
            assertTrue(result.javaClass === WebPreviewResult::class.java)
            assertEquals("APK Web provider", result.presentation)
            runtime.pluginManager.setEnabled("fixture.web-host", false)
            assertFailsWith<IllegalStateException> { old.list() }
            assertFails { host.launch(WebPreviewRequest("/workspace/index.html", "disabled")) }
            runtime.pluginManager.setEnabled("fixture.web-host", true)
            assertSame(host, runtime.webContainerController)
            assertTrue(current !== old)
            assertEquals("APK Web provider", host.list().single().title)
            runtime.pluginManager.uninstall("fixture.web-host")
            assertFails { host.list() }
        } finally {
            runtime.close()
            apk.setWritable(true)
            directory.deleteRecursively()
        }
        assertFailsWith<IllegalStateException> { host.list() }
    }
}

class AndroidFixtureHostWebController : Plugin<String> {
    override val name = "fixture-web-host"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        WebContainersProviderPlugin.apply(ctx, object : WebContainerController {
            override suspend fun launch(request: WebPreviewRequest) = WebPreviewResult("apk", request.entryPath, 0, config)
            override suspend fun list() = listOf(WebContainerInfo("apk", "/workspace/index.html", config, config, WebContainerState.Foreground))
            override suspend fun screenshot(containerId: String) = error("Unused")
            override suspend fun inspect(containerId: String) = error("Unused")
            override suspend fun interact(request: WebInteractionRequest) = error("Unused")
            override suspend fun console(containerId: String, cursor: Long, limit: Int) = error("Unused")
            override suspend fun setState(containerId: String, state: WebContainerState) = error("Unused")
            override suspend fun close(containerId: String) = Unit
            override suspend fun closeAll() = Unit
        }, effect)
    }
}
