package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test

class AndroidPluginPackageIntegrationTest {
    @Test(timeout = 120000)
    fun dualTargetArchiveSelectsIndependentApkAndRestoresPackageLock(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "package-integration-${System.nanoTime()}").also { it.mkdirs() }
        val name = "provider.message-codec.envelope-1.0.0.kplugin"
        val archive = File(directory, name)
        instrumentation.context.assets.open(name).use { input -> archive.outputStream().use { input.copyTo(it) } }
        val sha = instrumentation.context.assets.open("$name.sha256").use { it.readBytes().decodeToString().trim() }
        lateinit var codec: ChatMessageCodec
        val capture = kcodePlugin(PluginDescriptor("test.package-codec", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-package-codec", inject = dependencies(KcodeMessageCodec.Key)) { ctx, _ -> codec = ctx.require(KcodeMessageCodec.Key).codec }, Unit)
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture, kcodePlugin(
                PluginDescriptor("provider.plugin-packages.platform", "test", "test", emptySet()),
                NativePluginPackagesPlugin(directory, androidPackageHost(), artifactVerifier = androidPackageVerifier(context)), Unit,
            )),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory -> AndroidDynamicPluginController(ctx, context, loader, inventory, directory) },
        )
        var runtime: KcodePluginRuntime? = null
        try {
            runtime = KcodePluginRuntime.create(configuration())
            val builtIn = codec
            runtime.pluginManager.importPackages(listOf(PluginPackageImport(archive.absolutePath, sha)))
            val external = codec
            assertNotSame(builtIn.javaClass.classLoader, external.javaClass.classLoader)
            assertNotSame(ChatMessageCodec::class.java.classLoader, external.javaClass.classLoader)
            val spec = runtime.pluginManager.installed().single()
            assertEquals("android", spec.packageInstallation?.variantId)
            assertTrue(spec.artifactPath.endsWith("targets/android/plugin.apk"))
            assertTrue(spec.dependencies.isEmpty())
            val message = ChatMessage(1, MessageRole.Assistant, "independent APK")
            assertEquals(message.content, external.decode(external.encode(message)).text)
            runtime.pluginManager.setEnabled(spec.id, false)
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            assertFalse(runtime.pluginManager.installed().single().enabled)
            runtime.pluginManager.setEnabled(spec.id, true)
            assertEquals(message.content, codec.decode(codec.encode(message)).text)
            assertNotSame(external, codec)
            runtime.pluginManager.uninstall(spec.id)
            assertTrue(runtime.pluginManager.installed().isEmpty())
        } finally {
            runtime?.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
