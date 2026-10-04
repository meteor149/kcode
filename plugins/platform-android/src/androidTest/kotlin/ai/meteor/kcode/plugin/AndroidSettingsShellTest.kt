package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.createAndroidKoogChatRuntime
import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsUbuntuShellPlugin
import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsShellPlugin
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.provider.SettingsShellProviderPlugin
import ai.meteor.kcode.plugin.provider.SettingsUbuntuShellProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.SettingsProviderPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidSettingsShellTest {
    @Test(timeout = 60_000)
    fun nativeFactoryExecutesAppShellUsingCurrentSettingsAndRevokesBothWorlds(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-settings-shell-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        lateinit var shell: ShellBackend
        lateinit var ubuntu: ShellBackend
        val capture = kcodePlugin(descriptor("provider.ui.compose"), plugin<Unit>(
            name = "capture-native-shell", inject = dependencies(KcodeShell.Key, KcodeUbuntuShell.Key),
        ) { ctx, _ -> shell = ctx.require(KcodeShell.Key).executor; ubuntu = ctx.require(KcodeUbuntuShell.Key).executor }, Unit)
        try {
            withContext(Dispatchers.Main.immediate) {
                val activity = object : Activity() {
                    override fun getApplicationContext(): android.content.Context = isolated
                    override fun getFilesDir() = directory
                }
                val runtime = createAndroidKoogChatRuntime(
                    activity = activity, modeProvider = { error("obsolete host shell mode") },
                    toolCallApprover = ToolCallApprover { true }, settingsStore = MemorySettings(ShellExecutionMode.App),
                    settingsBackedShell = true, profile = KcodePluginProfile(overrides = listOf(capture)),
                )
                try {
                    val root = runtime.owner as KcodePluginRuntime
                    val result = shell.run(ShellRequest("id -u"))
                    assertEquals(0, result.exitCode)
                    assertTrue(result.output.contains("uid=${Process.myUid()}"))
                    val oldShell = shell
                    val oldUbuntu = ubuntu
                    root.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, MemorySettings(ShellExecutionMode.App)))
                    assertFailsWith<IllegalStateException> { oldShell.run(ShellRequest("false")) }
                    assertFailsWith<IllegalStateException> { oldUbuntu.run(ShellRequest("false")) }
                    assertEquals(0, shell.run(ShellRequest("printf settings-rebound")).exitCode)
                    root.pluginManager.setEnabled("provider.settings.platform", false)
                    assertEquals(PluginState.Pending, root.diagnostics().plugins.first { it.id == "provider.shell.platform" }.state)
                    assertEquals(PluginState.Pending, root.diagnostics().plugins.first { it.id == "provider.shell.ubuntu" }.state)
                } finally { runtime.close() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 60_000)
    fun isolatedApkShellProvidersRebindTheirSettingsAndRevokeOldReaders(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "settings-shell-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "shell.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        try {
            for (isUbuntu in listOf(false, true)) {
                lateinit var backend: ShellBackend
                val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
                    name = "capture-apk-shell", inject = if (isUbuntu) dependencies(KcodeUbuntuShell.Key) else dependencies(KcodeShell.Key),
                ) { ctx, _ -> backend = if (isUbuntu) ctx.require(KcodeUbuntuShell.Key).executor else ctx.require(KcodeShell.Key).executor }, Unit)
                val store = MemorySettings(ShellExecutionMode.App)
                val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                    interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                    settingsStore = store, featurePlugins = listOf(capture),
                    dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                        AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
                    },
                ))
                try {
                    runtime.pluginManager.install(DynamicPluginSpec(
                        id = "fixture.settings-shell", version = "test", entryClass = AndroidFixtureSettingsShell::class.java.name,
                        artifactPath = apk.path, config = if (isUbuntu) "ubuntu" else "system",
                        sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                        packageName = instrumentation.context.packageName,
                    ))
                    assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
                    assertEquals("app", backend.run(ShellRequest("mode")).output)
                    store.save(StoredAppSettings(shellExecutionMode = "unknown-mode"))
                    assertEquals("app", backend.run(ShellRequest("mode")).output)
                    store.save(StoredAppSettings(shellExecutionMode = ShellExecutionMode.Root.code))
                    assertEquals("root", backend.run(ShellRequest("mode")).output)
                    val previous = backend
                    runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, MemorySettings(ShellExecutionMode.Adb)))
                    assertFailsWith<IllegalStateException> { previous.run(ShellRequest("stale")) }
                    assertEquals("adb", backend.run(ShellRequest("mode")).output)
                    val beforeUninstall = backend
                    runtime.pluginManager.uninstall("fixture.settings-shell")
                    assertFailsWith<IllegalStateException> { beforeUninstall.run(ShellRequest("stale")) }
                } finally { runtime.close() }
            }
        } finally { apk.setWritable(true); directory.deleteRecursively() }
    }

    @Test(timeout = 60_000)
    fun isolatedApkRunsThePrivateNativeExecutorAndRevokesIt(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-shell-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "native-shell.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var backend: ShellBackend
        val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
            name = "capture-private-native-shell", inject = dependencies(KcodeShell.Key),
        ) { ctx, _ -> backend = ctx.require(KcodeShell.Key).executor }, Unit)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
        }
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() {
                override fun getApplicationContext(): android.content.Context = isolated
            }
        }
        val inputs = AndroidPluginHostInputs(activity)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            hostInputs = inputs,
            settingsStore = MemorySettings(ShellExecutionMode.App), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.private-native-shell", version = "test",
                entryClass = AndroidNativeSettingsShellPlugin::class.java.name,
                artifactPath = apk.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                packageName = instrumentation.context.packageName,
            ))
            assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
            val result = backend.run(ShellRequest("id -u"))
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("uid=${Process.myUid()}"))
            val previous = backend
            runtime.pluginManager.setEnabled("fixture.private-native-shell", false)
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
            runtime.pluginManager.setEnabled("fixture.private-native-shell", true)
            assertEquals(0, backend.run(ShellRequest("printf reenabled")).exitCode)
            val rebound = backend
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, MemorySettings(ShellExecutionMode.App)))
            assertFailsWith<IllegalStateException> { rebound.run(ShellRequest("false")) }
            assertEquals(0, backend.run(ShellRequest("printf rebound")).exitCode)
            val ready = File(directory, "running.pid")
            val running = async(Dispatchers.IO) {
                backend.run(ShellRequest("echo $$ > '${ready.path}'; exec sleep 60"))
            }
            withTimeout(5_000) { while (!ready.exists() || ready.readText().trim().isEmpty()) delay(10) }
            val pid = ready.readText().trim().toInt()
            assertFalse(running.isCompleted)
            val finalBackend = backend
            withTimeout(5_000) { runtime.pluginManager.uninstall("fixture.private-native-shell") }
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
            val exited = assertFailsWith<ErrnoException> { Os.kill(pid, 0) }
            assertEquals(OsConstants.ESRCH, exited.errno)
            assertFailsWith<IllegalStateException> { finalBackend.run(ShellRequest("false")) }
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
        assertFailsWith<IllegalStateException> { inputs.activity() }
    }

    @Test(timeout = 180_000)
    fun isolatedApkRunsPrivateUbuntuAndJoinsActiveProcessesBeforeUnloading(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-shell-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "native-shell.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        lateinit var backend: ShellBackend
        val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
            name = "capture-private-native-shell", inject = dependencies(KcodeUbuntuShell.Key),
        ) { ctx, _ -> backend = ctx.require(KcodeUbuntuShell.Key).executor }, Unit)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getApplicationInfo() = instrumentation.context.applicationInfo
        }
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() {
                override fun getApplicationContext(): android.content.Context = isolated
            }
        }
        val inputs = AndroidPluginHostInputs(activity)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            hostInputs = inputs,
            settingsStore = MemorySettings(ShellExecutionMode.App), featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.private-native-ubuntu", version = "test",
                entryClass = AndroidNativeSettingsUbuntuShellPlugin::class.java.name,
                artifactPath = apk.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                packageName = instrumentation.context.packageName,
            ))
            assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
            val result = backend.run(ShellRequest("set -eu; . /etc/os-release; printf '%s\\n' \"\$PRETTY_NAME\"; python3 -c 'print(\"private-ubuntu-ok\")'"))
            assertEquals(0, result.exitCode, result.output)
            assertTrue(result.output.contains("Ubuntu 24.04"), result.output)
            assertTrue(result.output.contains("private-ubuntu-ok"), result.output)
            val previous = backend
            runtime.pluginManager.setEnabled("fixture.private-native-ubuntu", false)
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
            runtime.pluginManager.setEnabled("fixture.private-native-ubuntu", true)
            assertEquals(0, backend.run(ShellRequest("printf reenabled")).exitCode)
            val rebound = backend
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, MemorySettings(ShellExecutionMode.App)))
            assertFailsWith<IllegalStateException> { rebound.run(ShellRequest("false")) }
            assertEquals(0, backend.run(ShellRequest("printf rebound")).exitCode)
            val ready = File(directory, "agent_workspace/running.pid")
            val running = async(Dispatchers.IO) {
                backend.run(ShellRequest("echo $$ > /workspace/running.pid; exec sleep 60"))
            }
            withTimeout(10_000) { while (!ready.exists() || ready.readText().trim().isEmpty()) delay(10) }
            val pid = ready.readText().trim().toInt()
            assertFalse(running.isCompleted)
            val extracted = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("kcode-native-artifacts-") }.toSet()
            assertTrue(extracted.isNotEmpty())
            val temporary = File(directory, "ubuntu_runtime").listFiles().orEmpty().filter { it.name.startsWith("tmp-") }.toSet()
            assertTrue(temporary.isNotEmpty())
            val finalBackend = backend
            withTimeout(5_000) { runtime.pluginManager.uninstall("fixture.private-native-ubuntu") }
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
            assertTrue(extracted.none { it.exists() }, "Imported native resources must be released after command join")
            assertTrue(temporary.none { it.exists() })
            assertTrue(File(directory, "ubuntu_runtime/rootfs/usr/bin/python3").exists())
            val exited = assertFailsWith<ErrnoException> { Os.kill(pid, 0) }
            assertEquals(OsConstants.ESRCH, exited.errno)
            assertFailsWith<IllegalStateException> { finalBackend.run(ShellRequest("false")) }
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
        } finally { runtime.close(); apk.setWritable(true); directory.deleteRecursively() }
        assertFailsWith<IllegalStateException> { inputs.activity() }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private class MemorySettings(mode: ShellExecutionMode) : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        private var value = StoredAppSettings(shellExecutionMode = mode.code)
        override suspend fun load() = value
        override suspend fun save(settings: StoredAppSettings) { value = settings }
    }
}

class AndroidFixtureSettingsShell : Plugin<String> {
    override val name = "fixture-settings-shell"
    override val inject = dependencies(KcodeSettings.Key)
    override suspend fun apply(ctx: Context, config: String, effect: EffectScope) {
        val factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor = { mode ->
            object : AgentShellExecutor {
                override suspend fun execute(command: String, workingDirectory: String?) =
                    AgentShellExecutor.ExecutionResult(mode().code, 0)
            }
        }
        val provider = if (config == "ubuntu") SettingsUbuntuShellProviderPlugin
        else SettingsShellProviderPlugin
        provider.apply(ctx, factory, effect)
    }
}
