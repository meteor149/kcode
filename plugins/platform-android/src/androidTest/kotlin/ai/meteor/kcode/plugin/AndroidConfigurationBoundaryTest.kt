package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidConfigurationBoundaryTest {
    @Test(timeout = 60_000)
    fun textEntriesRejectMalformedDeploymentDataBeforeActivation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "text-config-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "text.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = packageFileSha256(artifact)
        val approval = toolApprovalConfig("Tool", "%s %s", "Allow", "Deny")
        val foreground = ai.meteor.kcode.plugin.notifications.generationForegroundConfig("Channel", "Title", "Text")
        val entries = listOf(
            Triple(NativeToolApprovalPlugin::class.java.name, approval, listOf(
                "not-json", "[]", "{}", toolApprovalConfig(" ", "%s %s", "Allow", "Deny"),
                toolApprovalConfig("%d", "%s %s", "Allow", "Deny"),
                approval.replace("\"Allow\"", "42"), approval.replace("\"Allow\"", "null"),
            )),
            Triple("ai.meteor.kcode.plugin.notifications.AndroidGenerationForegroundPlugin", foreground, listOf(
                "not-json", "[]", "{}", ai.meteor.kcode.plugin.notifications.generationForegroundConfig(" ", "Title", "Text"),
                foreground.replace("\"Title\"", "42"), foreground.replace("\"Title\"", "null"),
            )),
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            entries.forEachIndexed { index, (entry, valid, invalidConfigurations) ->
                val spec = DynamicPluginSpec(
                    id = "fixture.text.$index", version = "valid", entryClass = entry,
                    artifactPath = artifact.path, sha256 = digest,
                    packageName = instrumentation.context.packageName, config = valid, enabled = false,
                )
                for (enabled in listOf(false, true)) {
                    invalidConfigurations.forEach { invalid ->
                        assertFailsWith<RuntimeException>("$entry enabled=$enabled config=$invalid") {
                            runtime.pluginManager.install(spec.copy(config = invalid, enabled = enabled))
                        }
                        assertTrue(runtime.pluginManager.installed().isEmpty())
                        assertFalse(runtime.diagnostics().plugins.any { it.id == spec.id })
                    }
                }
                runtime.pluginManager.install(spec)
                assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                    runtime.diagnostics().plugins.first { it.id == spec.id }.state)
                runtime.pluginManager.uninstall(spec.id)
                if (index == 1) {
                    runtime.pluginManager.install(spec.copy(enabled = true))
                    assertEquals(ai.meteor.kcode.plugin.api.PluginState.Pending,
                        runtime.diagnostics().plugins.first { it.id == spec.id }.state)
                    runtime.pluginManager.uninstall(spec.id)
                }
            }
        } finally {
            try { runtime.close() } finally { artifact.setWritable(true); directory.deleteRecursively() }
        }
    }

    @Test(timeout = 60_000)
    fun unitEntriesRejectInvalidConfigEvenWhenDisabledOrWaitingForDependencies(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "unit-config-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "unit.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = packageFileSha256(artifact)
        val entries = listOf(
            "ai.meteor.kcode.plugin.settingsstorage.AndroidNativeSettingsPlugin",
            "ai.meteor.kcode.plugin.history.AndroidNativeHistoryPlugin",
            "ai.meteor.kcode.plugin.artifacts.AndroidNativeArtifactsPlugin",
            "ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsShellPlugin",
            "ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsUbuntuShellPlugin",
            "ai.meteor.kcode.plugin.webcontainer.native.AndroidNativeWebContainerPlugin",
            "ai.meteor.kcode.plugin.skills.WorkspaceSkillsPlugin",
            "ai.meteor.kcode.plugin.KoogAgentLoopPlugin",
            "ai.meteor.kcode.plugin.SystemPromptServicePlugin",
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            entries.forEachIndexed { index, entry ->
                for (enabled in listOf(false, true)) {
                    for (invalid in listOf(null, "unexpected-config")) {
                        assertFailsWith<RuntimeException>("$entry enabled=$enabled config=$invalid") {
                            runtime.pluginManager.install(DynamicPluginSpec(
                                id = "fixture.unit.$index", version = "invalid", entryClass = entry,
                                artifactPath = artifact.path, sha256 = digest,
                                packageName = instrumentation.context.packageName, config = invalid, enabled = enabled,
                            ))
                        }
                        assertTrue(runtime.pluginManager.installed().isEmpty())
                        assertFalse(runtime.diagnostics().plugins.any { it.id == "fixture.unit.$index" })
                    }
                }
                runtime.pluginManager.install(DynamicPluginSpec(
                    id = "fixture.unit.$index", version = "valid", entryClass = entry,
                    artifactPath = artifact.path, sha256 = digest,
                    packageName = instrumentation.context.packageName, config = Unit, enabled = false,
                ))
                assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                    runtime.diagnostics().plugins.first { it.id == "fixture.unit.$index" }.state)
                runtime.pluginManager.uninstall("fixture.unit.$index")
            }
        } finally {
            try { runtime.close() } finally { artifact.setWritable(true); directory.deleteRecursively() }
        }
    }

    @Test(timeout = 60_000)
    fun invalidApkConfigKeepsTheSameServiceAndActiveWork(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "configuration-apk-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "configuration.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = packageFileSha256(artifact)
        lateinit var current: ShellBackend
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(kcodePlugin(
                PluginDescriptor("fixture.configuration", "builtin", "native", setOf("shell")),
                AndroidValidatedShellFixture(), directory.absolutePath,
            ), kcodePlugin(
                PluginDescriptor("test.capture", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-config-shell", inject = dependencies(KcodeShell.Key)) { ctx, _ ->
                    current = ctx.require(KcodeShell.Key).executor
                }, Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val spec = DynamicPluginSpec(
            id = "fixture.configuration", version = "first", entryClass = AndroidValidatedShellFixture::class.java.name,
            artifactPath = artifact.path, sha256 = digest, packageName = instrumentation.context.packageName,
            config = directory.absolutePath,
        )
        try {
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.install(spec.copy(id = "fixture.disabled-invalid", config = "reject", enabled = false))
            }
            assertTrue(runtime.pluginManager.installed().isEmpty())
            assertFalse(runtime.diagnostics().plugins.any { it.id == "fixture.disabled-invalid" })
            val builtin = current
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.replace(spec.copy(version = "invalid-builtin", config = "reject"))
            }
            assertSame(builtin, current)
            assertEquals("builtin-preserved", builtin.run(ShellRequest("builtin-preserved")).output)
            runtime.pluginManager.replace(spec)
            val previous = current
            assertNotSame(AndroidValidatedShellFixture::class.java.classLoader, previous.javaClass.classLoader)
            val running = async(Dispatchers.IO) { previous.run(ShellRequest("wait")) }
            withTimeout(10_000) { while (!File(directory, "entered").exists()) delay(10) }
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(spec.copy(version = "rejected", config = "reject"))
            }
            assertSame(previous, current)
            assertFalse(running.isCompleted)
            assertFalse(File(directory, "cleaned").exists())
            assertEquals("first", runtime.pluginManager.installed().single().version)
            assertEquals("ordinary", previous.run(ShellRequest("ordinary")).output)
            runtime.pluginManager.setEnabled(spec.id, false)
            withTimeout(10_000) { running.join() }
            assertTrue(running.isCancelled)
            assertTrue(File(directory, "cleaned").isFile)
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("stale")) }
            runtime.pluginManager.setEnabled(spec.id, true)
            assertNotSame(previous, current)
            assertEquals("restored", current.run(ShellRequest("restored")).output)
        } finally {
            try { runtime.close() } finally { artifact.setWritable(true); directory.deleteRecursively() }
        }
    }
}

class AndroidValidatedShellFixture : Plugin<String> {
    override val name = "android-validated-shell-fixture"
    override val config = ConfigValidator<String> { value ->
        require(value != "reject") { "Invalid fixture configuration" }
        value
    }

    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        KcodeShell(ctx, ShellBackend { request -> owner.run {
            if (request.command == "wait") {
                File(config, "entered").writeText("started")
                try { awaitCancellation() } finally { File(config, "cleaned").writeText("finished") }
            }
            ShellResult(request.command, 0)
        } })
    }
}
