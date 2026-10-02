package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class AndroidPluginCompositionTest {
    @Test
    fun apkPluginReplacesBuiltInAndRollsBackFailedGeneration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "kcode-plugin-test-${System.nanoTime()}").apply { mkdirs() }
        val testApk = File(instrumentation.context.applicationInfo.sourceDir)
        fun artifact(name: String) = File(directory, name).also {
            testApk.copyTo(it)
            check(it.setReadOnly())
        }
        val first = artifact("first.apk")
        val second = artifact("second.apk")
        val failed = artifact("failed.apk")
        fun spec(file: File, entryClass: String, version: String) = DynamicPluginSpec(
            id = "consumer.tools.goal",
            version = version,
            entryClass = entryClass,
            artifactPath = file.path,
            sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
            packageName = instrumentation.context.packageName,
            capabilities = setOf("tools"),
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            val manager = runtime.pluginManager
            manager.replace(spec(first, AndroidFixtureOne::class.java.name, "1"))
            assertFalse("core/goal" in runtime.diagnostics().toolContributions)
            assertTrue("android/one" in runtime.diagnostics().toolContributions)
            manager.setEnabled("consumer.tools.goal", false)
            assertFalse("android/one" in runtime.diagnostics().toolContributions)
            manager.setEnabled("consumer.tools.goal", true)
            assertTrue("android/one" in runtime.diagnostics().toolContributions)
            manager.replace(spec(second, AndroidFixtureTwo::class.java.name, "2"))
            assertFalse("android/one" in runtime.diagnostics().toolContributions)
            assertTrue("android/two" in runtime.diagnostics().toolContributions)
            assertFailsWith<IllegalStateException> {
                manager.replace(spec(failed, AndroidFixtureFailure::class.java.name, "3"))
            }
            assertEquals("2", manager.installed().single().version)
            assertTrue("android/two" in runtime.diagnostics().toolContributions)
            manager.uninstall("consumer.tools.goal")
            manager.setEnabled("consumer.tools.goal", true)
            assertTrue("core/goal" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
            listOf(first, second, failed).forEach { it.setWritable(true); it.delete() }
            directory.delete()
        }
    }
}

class AndroidFixtureOne : Plugin<Unit> {
    override val name = "android-fixture-one"
    override val inject = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("android/one", ToolRegistry { }))
    }
}

class AndroidFixtureTwo : Plugin<Unit> {
    override val name = "android-fixture-two"
    override val inject = dependencies(KcodeTools.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeTools.Key).register("android/two", ToolRegistry { }))
    }
}

class AndroidFixtureFailure : Plugin<Unit> {
    override val name = "android-fixture-failure"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        error("fixture replacement failure")
    }
}
