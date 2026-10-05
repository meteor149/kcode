package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAndroidProfileHost
import ai.meteor.kcode.plugin.packages.androidPackageHost
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.nativefilesystem.AndroidNativeFileSystemPlugin
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.ConfigValidator
import org.cordis.dependencies
import org.cordis.plugin
import org.cordis.ServiceKey
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidProfileHostTest {
    @Test(timeout = 300_000)
    fun apkProvidersSwitchSettingsHistoryAndWorkspaceScopesAndRestartSelection(): Unit = runBlocking {
        val fixture = Fixture()
        val host = fixture.start()
        try {
            val facade = host.runtime
            val baseline = assertNotNull(fixture.repository.loadCommitted("native"))
            val originalSettings = fixture.settings.load()
            val scoped = baseline.definition.copy(id = "scoped-${System.nanoTime()}", dataScope = ProfileDataScope(workspace = "profile"))
            fixture.repository.saveDraft(scoped)
            host.switchTo(scoped.id)
            assertSame(facade, host.runtime)
            assertEquals(scoped.id, fixture.repository.selected())
            assertNotSame(AndroidNativeFileSystemPlugin::class.java.classLoader, fixture.fs.javaClass.classLoader)
            val saved = facade.applicationContent!!.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))).settings
            fixture.history.appendMessage(1, "Profile history", 1, "User", "scoped data")
            fixture.fs.writeBytes("/workspace/profile.txt", "from APK".encodeToByteArray())
            val workspace = File(fixture.directory, "workspaces/profile-${scoped.id}")
            assertEquals("from APK", File(workspace, "profile.txt").readText())
            val system = fixture.shell.run(ShellRequest("cat profile.txt"))
            assertEquals(0, system.exitCode, system.output)
            assertTrue(system.output.contains("from APK"), system.output)
            val alias = fixture.shell.run(ShellRequest("cat profile.txt", "/workspace"))
            assertEquals(0, alias.exitCode, alias.output)
            fixture.ubuntu?.let { ubuntu ->
                val result = ubuntu.run(ShellRequest("cat /workspace/profile.txt"))
                assertEquals(0, result.exitCode, result.output)
                assertTrue(result.output.contains("from APK"), result.output)
            }
            val oldStore = fixture.settings
            val oldFs = fixture.fs
            val oldShell = fixture.shell
            host.switchTo("native")
            assertEquals(originalSettings, fixture.settings.load())
            assertFailsWith<IllegalStateException> { oldStore.load() }
            assertFailsWith<IllegalStateException> { oldFs.exists("/workspace/profile.txt") }
            assertFailsWith<IllegalStateException> { oldShell.run(ShellRequest("true")) }
            assertTrue(!fixture.fs.exists("/workspace/profile.txt"))
            host.switchTo(scoped.id)
            assertEquals(saved, fixture.settings.load())
            assertEquals("scoped data", fixture.history.loadAll().single().messages.single().content)
            assertEquals("from APK", fixture.fs.readBytes("/workspace/profile.txt").decodeToString())
            assertEquals(listOf(1L, 2L), fixture.repository.generations(scoped.id))
            host.close()
            val restarted = fixture.start()
            try {
                assertEquals(scoped.id, restarted.state.value.profileId)
                assertEquals(saved, fixture.settings.load())
                assertEquals("scoped data", fixture.history.loadAll().single().messages.single().content)
                assertEquals("from APK", fixture.fs.readBytes("/workspace/profile.txt").decodeToString())
            } finally { restarted.close() }
        } finally {
            host.close()
            fixture.remove()
        }
    }

    @Test(timeout = 180_000)
    fun failedApkTargetAllocationRestoresLockedProvidersAndHistory(): Unit = runBlocking {
        val fixture = Fixture()
        val host = fixture.start()
        try {
            val baseline = assertNotNull(fixture.repository.loadCommitted("native"))
            val broken = baseline.definition.copy(id = "broken", patches = baseline.definition.patches +
                ProfileOperation.Configure("provider.ui.compose", JsonPrimitive("fail"), "string"))
            fixture.repository.saveDraft(broken)
            val before = fixture.repository.state()
            assertFailsWith<IllegalStateException> { host.switchTo("broken") }
            assertEquals(before, fixture.repository.state())
            assertEquals(baseline, fixture.repository.loadCommitted("native"))
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(1, fixture.live)
            assertEquals(0, fixture.shell.run(ShellRequest("true")).exitCode)
            assertTrue(host.diagnostics().plugins.any { it.id == "provider.fs.platform" })
        } finally {
            host.close()
            assertEquals(0, fixture.live)
            fixture.remove()
        }
    }

    @Test(timeout = 180_000)
    fun adbModeRejectsAppPrivateProfileWorkspaceBeforeRequestingAuthorization(): Unit = runBlocking {
        val fixture = Fixture()
        val host = fixture.start()
        try {
            val baseline = assertNotNull(fixture.repository.loadCommitted("native"))
            val scoped = baseline.definition.copy(id = "scoped", dataScope = ProfileDataScope(workspace = "profile"))
            fixture.repository.saveDraft(scoped)
            host.switchTo(scoped.id)
            fixture.mode = ShellExecutionMode.Adb
            val failure = assertFailsWith<IllegalArgumentException> { fixture.shell.run(ShellRequest("true")) }
            assertTrue(failure.message.orEmpty().contains("app-private workspace"))
            fixture.ubuntu?.let { ubuntu ->
                assertFailsWith<IllegalArgumentException> { ubuntu.run(ShellRequest("true")) }
            }
        } finally {
            host.close()
            fixture.remove()
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "profile-host-${System.nanoTime()}").apply { mkdirs() }
        private val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        lateinit var settings: AppSettingsStore
        lateinit var history: ConversationHistoryRepository
        lateinit var fs: FileSystemBackend
        lateinit var shell: ShellBackend
        var ubuntu: ShellBackend? = null
        var mode = ShellExecutionMode.App
        var live = 0
        private val capture = kcodePlugin(PluginDescriptor("provider.ui.compose", "test", "test", emptySet()),
            plugin<String>(validator = ConfigValidator { it }, inject = dependencies(*buildList<ServiceKey<*>> {
                add(KcodeSettings.Key)
                add(KcodeHistory.Key)
                add(KcodeFileSystem.Key)
                add(KcodeShell.Key)
                if (androidPackageHost().arch == "arm64") add(KcodeUbuntuShell.Key)
            }.toTypedArray())) { ctx, config ->
                live++
                collect { live-- }
                check(config != "fail") { "target allocation refused" }
                settings = ctx.require(KcodeSettings.Key).store
                history = ctx.require(KcodeHistory.Key).repository
                fs = ctx.require(KcodeFileSystem.Key).backend
                shell = ctx.require(KcodeShell.Key).executor
                ubuntu = ctx[KcodeUbuntuShell.Key]?.executor
            }, "okay")

        suspend fun start(): KcodeProfileHost = withContext(Dispatchers.Main.immediate) {
            val activity = object : Activity() {
                init { attachBaseContext(isolated) }
                override fun getApplicationContext(): android.content.Context = isolated
                override fun getFilesDir() = directory
                override fun getAssets() = context.assets
                override fun getResources() = context.resources
            }
            createAndroidProfileHost(activity = activity, modeProvider = { mode }, toolCallApprover = ToolCallApprover { true },
                profile = KcodePluginProfile(overrides = listOf(capture)))
        }

        fun remove() {
            require(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
