package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.RootAgentPath
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSubagents
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSubagentProviderPrivateLoadingTest {
    // The aggregate instrumentation APK also performs cold native catalogue initialization.
    @Test(timeout = 300_000)
    fun actualPackageOwnsConfiguredCapacityAndJoinsChildCleanupAcrossWithdrawalAndRestart(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "private-subagents-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "subagents.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var factory: SubagentCoordinatorFactory
        val capture = kcodePlugin(PluginDescriptor("test.private-subagents", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-private-subagents", inject = dependencies(KcodeSubagents.Key),
        ) { ctx, _ -> factory = ctx.require(KcodeSubagents.Key).factory }, Unit)
        val configuration = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture), pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration)
        val release = CompletableDeferred<Unit>()
        try {
            assertEquals(5, factory.maxConcurrency)
            val deployment = DynamicPluginSpec(
                id = "feature.subagents", version = "private-capacity", artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                entryClass = SubagentFeaturePlugin::class.java.name,
                config = Json.parseToJsonElement("""{"maxConcurrency":2}"""),
                packageName = instrumentation.context.packageName,
            )
            runtime.pluginManager.replace(deployment)
            val original = factory
            assertEquals(2, original.maxConcurrency)
            assertNotSame(SubagentCoordinatorFactory::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(InProcessSubagentProviderPlugin::class.java.classLoader, original.javaClass.classLoader)
            assertSame(original.javaClass.classLoader, Class.forName(
                "ai.meteor.kcode.plugin.ConcurrencyLimit", false, original.javaClass.classLoader,
            ).classLoader)
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val coordinator = original.create(CoroutineScope(coroutineContext), "root", {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }, {})
            assertSame(original.javaClass.classLoader, coordinator.javaClass.classLoader)
            assertSame(original.javaClass.classLoader, Class.forName(
                "ai.meteor.kcode.MultiAgentCoordinator", false, original.javaClass.classLoader,
            ).classLoader)
            coordinator.spawn(RootAgentPath, "one", "hold child", null)
            withTimeout(5_000) { entered.await() }
            assertFailsWith<IllegalArgumentException> {
                coordinator.spawn(RootAgentPath, "two", "cannot exceed configured two slots", null)
            }
            assertFailsWith<Exception> {
                runtime.pluginManager.replace(deployment.copy(config = Json.parseToJsonElement("""{"maxConcurrency":0}""")))
            }
            assertSame(original, factory)
            assertEquals(2, factory.maxConcurrency)
            assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("feature.subagents", false) }
            val stopping = async { coordinator.shutdown() }
            withTimeout(5_000) { cleaning.await() }
            assertFalse(stopping.isCompleted)
            assertFailsWith<IllegalStateException> { runtime.pluginManager.setEnabled("feature.subagents", false) }
            assertEquals(2, original.maxConcurrency)
            release.complete(Unit)
            withTimeout(5_000) { stopping.await() }
            runtime.pluginManager.setEnabled("feature.subagents", false)
            assertEquals(null, original.maxConcurrency)
            assertFailsWith<IllegalStateException> { original.create(CoroutineScope(coroutineContext), "stale", { "" }, {}) }
            assertFailsWith<IllegalStateException> { coordinator.list(RootAgentPath, null) }
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration)
            runtime.pluginManager.setEnabled("feature.subagents", true)
            assertNotSame(original, factory)
            assertEquals(2, factory.maxConcurrency)
            val restored = factory
            runtime.pluginManager.uninstall("feature.subagents")
            assertEquals(null, restored.maxConcurrency)
            runtime.pluginManager.setEnabled("feature.subagents", true)
            assertEquals(5, factory.maxConcurrency)
        } finally {
            release.complete(Unit)
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
