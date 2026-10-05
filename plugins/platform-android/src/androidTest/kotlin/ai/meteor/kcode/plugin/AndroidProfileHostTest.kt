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
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSource
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
    fun injectedManagementCommandsSurviveWithdrawalAndShareSdkIdentityWithApkProviders(): Unit = runBlocking {
        val fixture = Fixture()
        val host = fixture.start()
        try {
            val old = fixture.profiles
            val catalogue = old.catalogue()
            val id = "commands-${System.nanoTime()}"
            val cloned = old.clone(ProfileCloneRequest(ProfileTarget("native"), id, catalogue.revision))
            val ticket = old.submit(ProfileCommand.Activate(ProfileActivationRequest(ProfileTarget(id, ProfileSource.Draft), cloned.revision)))
            val observer = async(start = CoroutineStart.UNDISPATCHED) { ticket.await() }
            observer.cancelAndJoin()
            val result = ticket.await()
            assertEquals(ProfileCommandPhase.Succeeded, result.phase)
            assertEquals(id, result.result!!.definition.id)
            assertEquals(1, fixture.live)
            assertFailsWith<IllegalStateException> { old.catalogue() }
            assertFailsWith<IllegalStateException> { old.submit(ProfileCommand.Activate(ProfileActivationRequest(ProfileTarget("native"), cloned.revision))) }
            assertEquals(id, fixture.profiles.catalogue().activeProfileId)
            assertEquals(ProfileModuleSource.Package, fixture.profiles.modules().single { it.id == "provider.fs.platform" }.source)
            assertSame(KcodeProfiles::class.java, checkNotNull(fixture.fs.javaClass.classLoader).loadClass(KcodeProfiles::class.java.name))
            val current = result.result!!
            val previousFilesystem = fixture.fs
            val moved = fixture.profiles.submit(ProfileCommand.Edit(ProfileCompositionEdit(id, current.generation, listOf(
                ProfileOperation.Insert(listOf(ProfileEntry("workspace-tools", "core.group", children = emptyList())), position = 0),
                ProfileOperation.Move("provider.fs.platform", "workspace-tools"),
            )))).await()
            assertEquals(ProfileCommandPhase.Succeeded, moved.phase)
            assertEquals(current.generation + 1, moved.result!!.generation)
            assertEquals(1, fixture.live)
            assertFailsWith<IllegalStateException> { previousFilesystem.readBytes("/workspace/commands.txt") }
            assertEquals("provider.fs.platform", fixture.profiles.modules().single { it.id == "provider.fs.platform" }.instances.single())
            assertSame(ProfileOperation.Move::class.java, checkNotNull(fixture.fs.javaClass.classLoader).loadClass(ProfileOperation.Move::class.java.name))
            fixture.fs.writeBytes("/workspace/commands.txt", "host owned command".encodeToByteArray())
            assertEquals(0, fixture.shell.run(ShellRequest("cat commands.txt")).exitCode)
            withTimeout(10_000) { fixture.profiles.commands.first { values -> values.any { it.id == ticket.id && it.phase == ProfileCommandPhase.Succeeded } } }
        } finally { host.close(); fixture.remove() }
    }

    @Test(timeout = 300_000)
    fun typedAlternateCatalogueRetainsSelectionAndApkDataAcrossSwitchingAndRestart(): Unit = runBlocking {
        val fixture = Fixture()
        var created = 0
        val factories = mapOf<String, () -> KcodePluginMount>("example.ui.alternate" to {
            created++
            fixture.alternate()
        })
        val host = fixture.start(factories)
        try {
            assertEquals(1, fixture.live)
            assertEquals(1, created)
            assertTrue(host.diagnostics().plugins.none { it.id == "example.ui.alternate" })
            val manager = host.pluginManager
            val catalogue = manager.profileCatalogue()
            val scopedId = "typed-${System.nanoTime()}"
            manager.cloneProfile(ProfileCloneRequest(ProfileTarget("native"), scopedId, catalogue.revision))
            host.switchTo(scopedId)
            val before = assertNotNull(manager.currentProfile())
            val selected = host.selectProfileModule("provider.ui.compose", "example.ui.alternate", before)
            assertEquals(before.generation + 1, selected.generation)
            assertEquals(1, fixture.live)
            assertEquals(2, created)
            assertFailsWith<IllegalArgumentException> { host.selectProfileModule("example.ui.alternate", "provider.ui.compose", before) }
            fixture.history.appendMessage(1, "Typed alternate", 1, "User", "alternate APK data")
            fixture.fs.writeBytes("/workspace/alternate.txt", "alternate workspace".encodeToByteArray())
            assertEquals(0, fixture.shell.run(ShellRequest("cat alternate.txt")).exitCode)
            assertNotSame(FileSystemBackend::class.java.classLoader, fixture.fs.javaClass.classLoader)
            val oldFs = fixture.fs
            val otherId = "other-${System.nanoTime()}"
            manager.cloneProfile(ProfileCloneRequest(ProfileTarget(scopedId), otherId, manager.profileCatalogue().revision))
            host.switchTo(otherId)
            assertTrue(fixture.history.loadAll().isEmpty())
            assertFailsWith<IllegalStateException> { oldFs.readBytes("/workspace/alternate.txt") }
            host.switchTo(scopedId)
            assertEquals("alternate APK data", fixture.history.loadAll().single().messages.single().content)
            assertEquals("alternate workspace", fixture.fs.readBytes("/workspace/alternate.txt").decodeToString())
            assertEquals(4, created)
            host.close()
            assertEquals(0, fixture.live)
            val missing = assertFailsWith<IllegalStateException> { fixture.start() }
            assertTrue(missing.message.orEmpty().contains("No package release available for 'example.ui.alternate'"))
            assertEquals(0, fixture.live)
            val restarted = fixture.start(factories)
            try {
                assertEquals(scopedId, restarted.state.value.profileId)
                assertEquals(5, created)
                assertEquals(1, fixture.live)
                assertEquals("alternate APK data", fixture.history.loadAll().single().messages.single().content)
                assertEquals("alternate workspace", fixture.fs.readBytes("/workspace/alternate.txt").decodeToString())
                assertTrue(assertNotNull(restarted.pluginManager.currentProfile()).definition.patches.any {
                    it is ProfileOperation.Replace && it.packageId == "example.ui.alternate"
                })
            } finally { restarted.close() }
        } finally { host.close(); fixture.remove() }
    }

    @Test(timeout = 300_000)
    fun sdkActivatesDraftAndHistoricalRecipesWithActualApkProviders(): Unit = runBlocking {
        val fixture = Fixture()
        val host = fixture.start()
        try {
            val manager = host.pluginManager
            val id = "managed-${System.nanoTime()}"
            val initial = manager.profileCatalogue()
            val cloned = manager.cloneProfile(ProfileCloneRequest(ProfileTarget("native"), id, initial.revision))
            val beforePreview = fixture.repository.state()
            val preview = manager.previewProfile(ProfileTarget(id, ProfileSource.Draft))
            assertTrue(preview.packagesVerified)
            assertTrue(preview.diagnostics.isEmpty())
            assertEquals(beforePreview, fixture.repository.state())
            assertEquals(1, fixture.live)
            val original = manager.activateProfile(ProfileActivationRequest(ProfileTarget(id, ProfileSource.Draft), cloned.revision))
            assertEquals(1L, original.generation)
            fixture.fs.writeBytes("/workspace/sdk.txt", "SDK activation".encodeToByteArray())
            assertEquals(0, fixture.shell.run(ShellRequest("cat sdk.txt")).exitCode)
            val changed = original.definition.copy(displayName = "Edited")
            val saved = manager.writeProfileDraft(ProfileDraftWrite(changed, manager.profileCatalogue().revision))
            assertEquals(original, manager.currentProfile())
            assertEquals(2L, manager.activateProfile(ProfileActivationRequest(ProfileTarget(id, ProfileSource.Draft), saved.revision)).generation)
            val restored = manager.activateProfile(ProfileActivationRequest(ProfileTarget(id, ProfileSource.History, 1), manager.profileCatalogue().revision))
            assertEquals(3L, restored.generation)
            assertEquals(original.definition, restored.definition)
            assertEquals("SDK activation", fixture.fs.readBytes("/workspace/sdk.txt").decodeToString())
            assertEquals(listOf(1L, 2L, 3L), manager.profileHistory(id).map { it.generation })
            assertFailsWith<IllegalArgumentException> { manager.deleteProfile(id, manager.profileCatalogue().revision) }
            manager.activateProfile(ProfileActivationRequest(ProfileTarget("native"), manager.profileCatalogue().revision))
            manager.deleteProfile(id, manager.profileCatalogue().revision)
            assertTrue(fixture.repository.generations(id).isEmpty())
            assertEquals("native", fixture.repository.selected())
        } finally { host.close(); fixture.remove() }
    }

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
        lateinit var profiles: ProfileManagementClient
        var ubuntu: ShellBackend? = null
        var mode = ShellExecutionMode.App
        var live = 0
        private fun captureModule(id: String) = kcodePlugin(PluginDescriptor(id, "test", "test", emptySet()),
            plugin<String>(validator = ConfigValidator { it }, inject = dependencies(*buildList<ServiceKey<*>> {
                add(KcodeSettings.Key)
                add(KcodeHistory.Key)
                add(KcodeFileSystem.Key)
                add(KcodeShell.Key)
                add(KcodeProfiles.Key)
                if (androidPackageHost().arch == "arm64") add(KcodeUbuntuShell.Key)
            }.toTypedArray())) { ctx, config ->
                live++
                collect { live-- }
                check(config != "fail") { "target allocation refused" }
                settings = ctx.require(KcodeSettings.Key).store
                history = ctx.require(KcodeHistory.Key).repository
                fs = ctx.require(KcodeFileSystem.Key).backend
                shell = ctx.require(KcodeShell.Key).executor
                profiles = ctx.require(KcodeProfiles.Key).client
                ubuntu = ctx[KcodeUbuntuShell.Key]?.executor
            }, "okay")

        private val capture = captureModule("provider.ui.compose")
        fun alternate(): KcodePluginMount = captureModule("example.ui.alternate")

        suspend fun start(moduleFactories: Map<String, () -> KcodePluginMount> = emptyMap()): KcodeProfileHost = withContext(Dispatchers.Main.immediate) {
            val activity = object : Activity() {
                init { attachBaseContext(isolated) }
                override fun getApplicationContext(): android.content.Context = isolated
                override fun getFilesDir() = directory
                override fun getAssets() = context.assets
                override fun getResources() = context.resources
            }
            createAndroidProfileHost(activity = activity, modeProvider = { mode }, toolCallApprover = ToolCallApprover { true },
                profile = KcodePluginProfile(overrides = listOf(capture)), moduleFactories = moduleFactories)
        }

        fun remove() {
            require(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
