package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.plugin.packages.NativePluginPackagesPlugin
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.packages.androidPackageVerifier
import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.createAndroidKoogChatRuntime
import ai.meteor.kcode.plugin.nativeexecution.AndroidNativeSettingsUbuntuShellPlugin
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeShell
import kotlinx.serialization.json.Json
import ai.meteor.kcode.plugin.executionsettings.SettingsShellModePlugin
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.ShellModePolicy
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
import ai.meteor.kcode.test.LegacySettings
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
import java.util.zip.ZipFile
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
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidSettingsShellTest {
    @Test(timeout = 60_000)
    fun nativeFactoryExecutesAppShellUsingCurrentSettingsAndRevokesBothWorlds(): Unit = runBlocking {
        assumeTrue(androidPackageHost().arch == "arm64")
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
                    override fun getAssets() = context.assets
                    override fun getResources() = context.resources
                }
                val runtime = createAndroidKoogChatRuntime(
                    activity = activity, modeProvider = { error("obsolete host shell mode") },
                    toolCallApprover = ToolCallApprover { true }, settingsStore = MemorySettings(ShellExecutionMode.App),
                    settingsBackedShell = true, profile = KcodePluginProfile(overrides = listOf(capture)),
                )
                try {
                    val root = runtime.owner as KcodePluginRuntime
                    assertTrue(shell.javaClass.classLoader !== ShellBackend::class.java.classLoader)
                    assertEquals("android", root.pluginManager.installed().single { it.id == "provider.shell.platform" }.packageInstallation?.variantId)
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
                    settingsStore = store, featurePlugins = listOf(
                        kcodePlugin(descriptor("test.settings-mode"), SettingsShellModePlugin, Unit), capture,
                    ),
                    dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                        AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
                    },
                ))
                try {
                    runtime.pluginManager.install(DynamicPluginSpec(
                        id = "fixture.settings-shell", version = "test", entryClass = AndroidFixtureSettingsShell::class.java.name,
                        artifactPath = apk.path, config = if (isUbuntu) "ubuntu" else "system",
                        sha256 = packageFileSha256(apk),
                        packageName = instrumentation.context.packageName,
                    ))
                    assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
            assertEquals(Class.forName("rikka.shizuku.Shizuku"), Class.forName("rikka.shizuku.Shizuku", false, backend.javaClass.classLoader))
            assertEquals(Class.forName("rikka.shizuku.ShizukuProvider"), Class.forName("rikka.shizuku.ShizukuProvider", false, backend.javaClass.classLoader))
                    assertEquals("app", backend.run(ShellRequest("mode")).output)
                    store.save(LegacySettings(shellExecutionMode = "root", namespaces = mapOf(
                        "feature.execution-settings" to (Json.parseToJsonElement("""{"mode":"adb"}""") as kotlinx.serialization.json.JsonObject),
                    )))
                    assertEquals("adb", backend.run(ShellRequest("mode")).output)
                    store.save(LegacySettings(shellExecutionMode = "unknown-mode"))
                    assertEquals("app", backend.run(ShellRequest("mode")).output)
                    store.save(LegacySettings(shellExecutionMode = ShellExecutionMode.Root.code))
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
    fun isolatedArchiveRunsThePrivateNativeExecutorWithoutUbuntuPayloadAndRevokesIt(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-shell-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "native-shell.kplugin")
        instrumentation.context.assets.open("provider.shell.platform-1.0.0.kplugin").use { input -> apk.outputStream().use { input.copyTo(it) } }
        check(apk.setReadOnly())
        val modeArchive = File(directory, "mode.kplugin")
        instrumentation.context.assets.open("policy.shell-mode.platform-1.0.0.kplugin").use { input -> modeArchive.outputStream().use { input.copyTo(it) } }
        check(modeArchive.setReadOnly())
        lateinit var backend: ShellBackend
        lateinit var modePolicy: ShellModePolicy
        val store = MemorySettings(ShellExecutionMode.App)
        val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
            name = "capture-private-native-shell", inject = dependencies(KcodeShell.Key, KcodeShellMode.Key),
        ) { ctx, _ -> backend = ctx.require(KcodeShell.Key).executor; modePolicy = ctx.require(KcodeShellMode.Key).policy }, Unit)
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
            settingsStore = store, featurePlugins = listOf(capture, kcodePlugin(
                descriptor("provider.plugin-packages.platform"),
                NativePluginPackagesPlugin(directory, androidPackageHost(), artifactVerifier = androidPackageVerifier(context)), Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.importPackages(listOf(
                PluginPackageImport(modeArchive.absolutePath, packageFileSha256(modeArchive)),
                PluginPackageImport(apk.absolutePath, packageFileSha256(apk)),
            ))
            val installed = runtime.pluginManager.installed().single { it.id == "provider.shell.platform" }
            assertEquals("android", installed.packageInstallation?.variantId)
            assertEquals("ai.meteor.kcode.external.nativeshell", installed.packageName)
            ZipFile(installed.artifactPath).use { payload ->
                val names = payload.entries().asSequence().map { it.name }.toList()
                assertTrue(names.any { it.endsWith(".dex") })
                assertFalse(names.any { it.startsWith("lib/") })
                assertFalse(names.any { it.contains("ubuntu-noble") || it.contains("PRoot-GPL") || it.contains("OperitTerminalCore") })
            }
            assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
            assertTrue(modePolicy.javaClass.classLoader !== ShellModePolicy::class.java.classLoader)
            assertTrue(modePolicy.javaClass.interfaces.contains(ShellModePolicy::class.java))
            val settingsPolicy = checkNotNull(modePolicy.settings)
            assertTrue(settingsPolicy.javaClass.classLoader !== ai.meteor.kcode.plugin.api.ShellModeSettingsPolicy::class.java.classLoader)
            val namespaced = settingsPolicy.update(LegacySettings(shellExecutionMode = "root"), ShellExecutionMode.App)
            store.save(namespaced)
            assertEquals(ShellExecutionMode.App, modePolicy.mode())
            store.save(LegacySettings(shellExecutionMode = "unknown-mode"))
            assertEquals(ShellExecutionMode.App, modePolicy.mode())
            store.save(LegacySettings(shellExecutionMode = ShellExecutionMode.Root.code))
            assertEquals(ShellExecutionMode.Root, modePolicy.mode())
            store.save(LegacySettings(shellExecutionMode = ShellExecutionMode.App.code))
            assertEquals(Class.forName("rikka.shizuku.Shizuku"), Class.forName("rikka.shizuku.Shizuku", false, backend.javaClass.classLoader))
            assertEquals(Class.forName("rikka.shizuku.ShizukuProvider"), Class.forName("rikka.shizuku.ShizukuProvider", false, backend.javaClass.classLoader))
            val result = backend.run(ShellRequest("id -u"))
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("uid=${Process.myUid()}"))
            val previous = backend
            runtime.pluginManager.setEnabled("provider.shell.platform", false)
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
            runtime.pluginManager.setEnabled("provider.shell.platform", true)
            assertEquals(0, backend.run(ShellRequest("printf reenabled")).exitCode)
            val rebound = backend
            val oldModePolicy = modePolicy
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, MemorySettings(ShellExecutionMode.App)))
            assertFailsWith<IllegalStateException> { oldModePolicy.mode() }
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
            withTimeout(5_000) { runtime.pluginManager.uninstall("provider.shell.platform") }
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
            val exited = assertFailsWith<ErrnoException> { Os.kill(pid, 0) }
            assertEquals(OsConstants.ESRCH, exited.errno)
            assertFailsWith<IllegalStateException> { finalBackend.run(ShellRequest("false")) }
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
        } finally { runtime.close(); apk.setWritable(true); modeArchive.setWritable(true); directory.deleteRecursively() }
        assertFailsWith<IllegalStateException> { inputs.activity() }
    }

    @Test(timeout = 60_000)
    fun callbackModeWorksWithoutSettingsAndWithdrawalCancelsItsPrivateShell(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "callback-shell-${System.nanoTime()}").apply { mkdirs() }
        val archive = File(directory, "shell.kplugin")
        instrumentation.context.assets.open("provider.shell.platform-1.0.0.kplugin").use { input -> archive.outputStream().use { input.copyTo(it) } }
        check(archive.setReadOnly())
        var mode = ShellExecutionMode.App
        lateinit var backend: ShellBackend
        lateinit var reader: ShellModePolicy
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): android.content.Context = context }
        }
        val capture = kcodePlugin(descriptor("test.capture"), plugin<Unit>(
            name = "capture-callback-shell", inject = dependencies(KcodeShell.Key, KcodeShellMode.Key),
        ) { ctx, _ -> backend = ctx.require(KcodeShell.Key).executor; reader = ctx.require(KcodeShellMode.Key).policy }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            hostInputs = AndroidPluginHostInputs(activity),
            featurePlugins = listOf(capture, kcodePlugin(
                descriptor("policy.shell-mode.platform"), HostShellModeInputPlugin(), { mode },
            ), kcodePlugin(
                descriptor("provider.plugin-packages.platform"),
                NativePluginPackagesPlugin(directory, androidPackageHost(), artifactVerifier = androidPackageVerifier(context)), Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            runtime.pluginManager.importPackages(listOf(PluginPackageImport(archive.absolutePath, packageFileSha256(archive))))
            assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
            mode = ShellExecutionMode.Root
            assertEquals(ShellExecutionMode.Root, reader.mode())
            mode = ShellExecutionMode.App
            assertEquals(0, backend.run(ShellRequest("printf callback-mode")).exitCode)
            val previous = backend
            val previousReader = reader
            val ready = File(directory, "running.pid")
            val running = async(Dispatchers.IO) { previous.run(ShellRequest("echo $$ > '${ready.path}'; exec sleep 60")) }
            withTimeout(5_000) { while (!ready.exists() || ready.readText().trim().isEmpty()) delay(10) }
            val pid = ready.readText().trim().toInt()
            withTimeout(5_000) { runtime.pluginManager.setEnabled("policy.shell-mode.platform", false) }
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
            assertEquals(OsConstants.ESRCH, assertFailsWith<ErrnoException> { Os.kill(pid, 0) }.errno)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.single { it.id == "provider.shell.platform" }.state)
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
            assertFailsWith<IllegalStateException> { previousReader.mode() }
            runtime.pluginManager.setEnabled("policy.shell-mode.platform", true)
            assertTrue(backend !== previous)
            assertEquals(0, backend.run(ShellRequest("printf callback-restored")).exitCode)
        } finally { runtime.close(); archive.setWritable(true); directory.deleteRecursively() }
    }

    @Test(timeout = 180_000)
    fun isolatedApkRunsPrivateUbuntuAndJoinsActiveProcessesBeforeUnloading(): Unit = runBlocking {
        assumeTrue(androidPackageHost().arch == "arm64")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-shell-apk-${System.nanoTime()}").apply { mkdirs() }
        val archive = File(directory, "native-ubuntu.kplugin")
        instrumentation.context.assets.open("provider.shell.ubuntu-1.0.0.kplugin").use { input -> archive.outputStream().use { input.copyTo(it) } }
        check(archive.setReadOnly())
        val modeArchive = File(directory, "mode.kplugin")
        instrumentation.context.assets.open("policy.shell-mode.platform-1.0.0.kplugin").use { input -> modeArchive.outputStream().use { input.copyTo(it) } }
        check(modeArchive.setReadOnly())
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
            settingsStore = MemorySettings(ShellExecutionMode.App), featurePlugins = listOf(capture, kcodePlugin(
                descriptor("provider.plugin-packages.platform"),
                NativePluginPackagesPlugin(directory, androidPackageHost(), artifactVerifier = androidPackageVerifier(context)), Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            val sha = packageFileSha256(archive)
            runtime.pluginManager.importPackages(listOf(
                PluginPackageImport(modeArchive.absolutePath, packageFileSha256(modeArchive)),
                PluginPackageImport(archive.absolutePath, sha),
            ))
            assertEquals("android", runtime.pluginManager.installed().single { it.id == "provider.shell.ubuntu" }.packageInstallation?.variantId)
            assertTrue(backend.javaClass.classLoader !== ShellBackend::class.java.classLoader)
            assertEquals(Class.forName("rikka.shizuku.Shizuku"), Class.forName("rikka.shizuku.Shizuku", false, backend.javaClass.classLoader))
            assertEquals(Class.forName("rikka.shizuku.ShizukuProvider"), Class.forName("rikka.shizuku.ShizukuProvider", false, backend.javaClass.classLoader))
            val result = backend.run(ShellRequest("set -eu; . /etc/os-release; printf '%s\\n' \"\$PRETTY_NAME\"; python3 -c 'print(\"private-ubuntu-ok\")'"))
            assertEquals(0, result.exitCode, result.output)
            assertTrue(result.output.contains("Ubuntu 24.04"), result.output)
            assertTrue(result.output.contains("private-ubuntu-ok"), result.output)
            val previous = backend
            runtime.pluginManager.setEnabled("provider.shell.ubuntu", false)
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
            runtime.pluginManager.setEnabled("provider.shell.ubuntu", true)
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
            withTimeout(5_000) { runtime.pluginManager.uninstall("provider.shell.ubuntu") }
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
            assertTrue(extracted.none { it.exists() }, "Imported native resources must be released after command join")
            assertTrue(temporary.none { it.exists() })
            assertTrue(File(directory, "ubuntu_runtime/rootfs/usr/bin/python3").exists())
            val exited = assertFailsWith<ErrnoException> { Os.kill(pid, 0) }
            assertEquals(OsConstants.ESRCH, exited.errno)
            assertFailsWith<IllegalStateException> { finalBackend.run(ShellRequest("false")) }
            assertFailsWith<IllegalStateException> { previous.run(ShellRequest("false")) }
        } finally { runtime.close(); archive.setWritable(true); modeArchive.setWritable(true); directory.deleteRecursively() }
        assertFailsWith<IllegalStateException> { inputs.activity() }
    }

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private class MemorySettings(mode: ShellExecutionMode) : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        private var value = LegacySettings(shellExecutionMode = mode.code)
        override suspend fun load() = value
        override suspend fun save(settings: StoredAppSettings) { value = settings }
    }
}

class AndroidFixtureSettingsShell : Plugin<String> {
    override val name = "fixture-settings-shell"
    override val inject = dependencies(KcodeShellMode.Key)
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
