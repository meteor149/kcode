package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginHostInputs
import ai.meteor.kcode.plugin.api.PluginCodeOrigin
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPluginCodeOriginTest {
    @Test(timeout = 90_000)
    fun privateApkOriginSurvivesReplacementRollbackAndRestart(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "code-origin-${System.nanoTime()}").apply { mkdirs() }
        fun artifact(name: String) = File(directory, name).also {
            File(instrumentation.context.applicationInfo.sourceDir).copyTo(it)
            check(it.setReadOnly())
        }
        val first = artifact("first.apk")
        val second = artifact("second.apk")
        fun sha(file: File) = packageFileSha256(file)
        fun spec(file: File, version: String, config: String) = DynamicPluginSpec(
            id = "fixture.origin", version = version, config = config,
            artifactPath = file.path, sha256 = sha(file), packageName = instrumentation.context.packageName,
            entryClass = AndroidFixtureCodeOrigin::class.java.name,
        )
        lateinit var backend: ShellBackend
        val capture = kcodePlugin(PluginDescriptor("test.capture-origin", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-origin", inject = dependencies(KcodeShell.Key),
        ) { ctx, _ ->
            assertEquals(null, PluginCodeOrigin.current(ctx))
            backend = ctx.require(KcodeShell.Key).executor
        }, Unit)
        val activity = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            object : android.app.Activity() {
                override fun getApplicationContext(): android.content.Context = context
            }
        }
        var inputs = AndroidPluginHostInputs(activity)
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }), hostInputs = inputs, featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        )
        suspend fun assertOrigin(file: File, version: String, config: String) {
            assertEquals("${file.canonicalPath}|${sha(file)}|$version|$config", backend.run(ShellRequest("origin")).output)
        }
        var runtime = KcodePluginRuntime.create(configuration())
        try {
            runtime.pluginManager.install(spec(first, "1", "first"))
            assertOrigin(first, "1", "first")
            runtime.pluginManager.replace(spec(second, "2", "second"))
            assertOrigin(second, "2", "second")
            assertFailsWith<IllegalStateException> { runtime.pluginManager.replace(spec(first, "3", "fail")) }
            assertOrigin(second, "2", "second")
            val retired = backend
            runtime.pluginManager.setEnabled("fixture.origin", false)
            assertFailsWith<IllegalStateException> { retired.run(ShellRequest("stale")) }
            runtime.pluginManager.setEnabled("fixture.origin", true)
            assertOrigin(second, "2", "second")
            runtime.close()
            assertFailsWith<IllegalStateException> { inputs.activity() }
            inputs = AndroidPluginHostInputs(activity)
            runtime = KcodePluginRuntime.create(configuration())
            assertOrigin(second, "2", "second")
            val beforeUninstall = backend
            runtime.pluginManager.uninstall("fixture.origin")
            assertFailsWith<IllegalStateException> { beforeUninstall.run(ShellRequest("stale")) }
            assertEquals(activity, inputs.activity())
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}

class AndroidFixtureCodeOrigin : Plugin<String> {
    override val name = "fixture-code-origin"
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        check(javaClass.classLoader !== PluginCodeOrigin::class.java.classLoader)
        val origin = requireNotNull(PluginCodeOrigin.current(ctx))
        val inputs = requireNotNull(PluginHostInputs.current(ctx, effect) as? AndroidPluginHostInputs)
        check(inputs.activity().applicationContext === inputs.applicationContext())
        check(origin.dependencies.isEmpty())
        if (config == "fail") error("origin fixture failure")
        KcodeShell(ctx, ShellBackend {
            inputs.activity()
            val artifact = origin.artifact
            ShellResult("${artifact.artifactPath}|${artifact.sha256}|${artifact.version}|$config", 0)
        })
    }
}
