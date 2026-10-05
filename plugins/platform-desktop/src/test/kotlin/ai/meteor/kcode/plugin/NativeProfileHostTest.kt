package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopKoogChatRuntime
import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.history.ConversationHistoryRepository
import org.cordis.dependencies
import org.cordis.plugin
import org.cordis.ConfigValidator
import kotlinx.serialization.json.JsonPrimitive

class NativeProfileHostTest {
    @Test
    fun sdkManagesDraftsPreviewsHistoryAndNativeActivationWithoutCopyingBusinessData(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-native-profile-management")
        lateinit var history: ConversationHistoryRepository
        var live = 0
        var allocations = 0
        val capture = kcodePlugin(PluginDescriptor("provider.ui.compose", "test", "test", emptySet()),
            plugin<String>(validator = ConfigValidator { require(it != "invalid"); it }, inject = dependencies(KcodeHistory.Key)) { context, config ->
                live++
                allocations++
                collect { live-- }
                check(config != "fail") { "allocation refused" }
                history = context.require(KcodeHistory.Key).repository
            }, "okay")
        val host = createDesktopProfileHost(homeDirectory = home, profile = KcodePluginProfile(overrides = listOf(capture)))
        try {
            val manager = host.pluginManager
            val baseline = assertNotNull(manager.currentProfile())
            history.appendMessage(1, "Native data", 1, "User", "native data")
            val initial = manager.profileCatalogue()
            assertEquals("native", initial.activeProfileId)
            assertFailsWith<IllegalArgumentException> { manager.deleteProfile("native", initial.revision) }
            val cloned = manager.cloneProfile(ProfileCloneRequest(ProfileTarget("native"), "managed", initial.revision))
            val draft = assertNotNull(manager.profileDraft("managed"))
            assertEquals("profile", draft.dataScope.history)
            assertEquals("profile", draft.dataScope.workspace)
            assertEquals(null, cloned.profiles.single { it.id == "managed" }.generation)
            val preview = manager.previewProfile(ProfileTarget("managed", ProfileSource.Draft))
            assertTrue(preview.packagesVerified)
            assertTrue(preview.diagnostics.isEmpty())
            assertEquals(cloned.revision, preview.revision)
            assertEquals(1, allocations)
            assertEquals(baseline, manager.currentProfile())
            assertFailsWith<IllegalArgumentException> {
                manager.activateProfile(ProfileActivationRequest(ProfileTarget("managed", ProfileSource.Draft), initial.revision))
            }
            val activated = manager.activateProfile(ProfileActivationRequest(ProfileTarget("managed", ProfileSource.Draft), cloned.revision))
            assertEquals(1L, activated.generation)
            assertEquals("managed", manager.profileCatalogue().selectedProfileId)
            assertTrue(history.loadAll().isEmpty())
            history.appendMessage(2, "Managed data", 2, "User", "managed data")
            val edited = activated.definition.copy(patches = activated.definition.patches + ProfileOperation.Disable("provider.ui.compose"))
            val saved = manager.writeProfileDraft(ProfileDraftWrite(edited, manager.profileCatalogue().revision))
            assertEquals(activated, manager.currentProfile())
            val changed = manager.activateProfile(ProfileActivationRequest(ProfileTarget("managed", ProfileSource.Draft), saved.revision))
            assertEquals(2L, changed.generation)
            assertEquals(0, live)
            val restored = manager.activateProfile(ProfileActivationRequest(ProfileTarget("managed", ProfileSource.History, 1), manager.profileCatalogue().revision))
            assertEquals(3L, restored.generation)
            assertEquals(activated.definition, restored.definition)
            assertEquals(listOf(1L, 2L, 3L), manager.profileHistory("managed").map { it.generation })
            assertEquals("managed data", history.loadAll().single().messages.single().content)
            val broken = restored.definition.copy(patches = restored.definition.patches +
                ProfileOperation.Configure("provider.ui.compose", JsonPrimitive("fail"), "string"))
            val beforeFailure = manager.writeProfileDraft(ProfileDraftWrite(broken, manager.profileCatalogue().revision))
            assertFailsWith<IllegalStateException> {
                manager.activateProfile(ProfileActivationRequest(ProfileTarget("managed", ProfileSource.Draft), beforeFailure.revision))
            }
            assertEquals(beforeFailure, manager.profileCatalogue())
            assertEquals(restored, manager.currentProfile())
            assertEquals(1, live)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            manager.activateProfile(ProfileActivationRequest(ProfileTarget("native"), manager.profileCatalogue().revision))
            assertEquals("native data", history.loadAll().single().messages.single().content)
            val dataFile = home.resolve("profile-data/profile-managed/history.db")
            assertTrue(Files.exists(dataFile))
            val deleted = manager.deleteProfile("managed", manager.profileCatalogue().revision)
            assertTrue(deleted.profiles.none { it.id == "managed" })
            assertTrue(Files.exists(dataFile))
            assertFailsWith<IllegalArgumentException> { manager.writeProfileDraft(ProfileDraftWrite(draft, beforeFailure.revision)) }
            val reopened = FileProfileRepository(home.resolve("profiles").toFile())
            assertEquals("native", reopened.selected())
            assertTrue(reopened.generations("managed").isEmpty())
        } finally {
            host.close()
            assertEquals(0, live)
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeFacadesSwitchScopesAndRestartTheSavedSelection(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-native-live-profile")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        lateinit var settings: AppSettingsStore
        lateinit var history: ConversationHistoryRepository
        val capture = kcodePlugin(PluginDescriptor("provider.conversation-overlay.platform", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeSettings.Key, KcodeHistory.Key)) { context, _ ->
                settings = context.require(KcodeSettings.Key).store
                history = context.require(KcodeHistory.Key).repository
            }, Unit)
        val hostProfile = KcodePluginProfile(overrides = listOf(capture))
        val host = createDesktopProfileHost(homeDirectory = home, profile = hostProfile)
        try {
            val facade = host.runtime
            val manager = assertNotNull(facade.pluginManager)
            val initialState = assertNotNull(manager.currentProfile())
            val baseline = assertNotNull(repository.loadCommitted("native"))
            val headless = baseline.definition.copy(id = "headless", dataScope = ProfileDataScope(),
                patches = baseline.definition.patches + ProfileOperation.Disable("provider.ui.compose"))
            repository.saveDraft(headless)
            host.switchTo("headless")
            assertSame(facade, host.runtime)
            assertEquals("headless", repository.selected())
            assertEquals("headless", manager.currentProfile()!!.definition.id)
            assertFailsWith<IllegalArgumentException> {
                manager.editProfile(ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit(
                    initialState.definition.id, initialState.generation, emptyList()))
            }
            val switchedState = assertNotNull(manager.currentProfile())
            assertEquals(switchedState, manager.editProfile(ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit(
                switchedState.definition.id, switchedState.generation, emptyList())))
            val saved = facade.applicationContent!!.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))).settings
            history.appendMessage(1, "Live scope", 1, "User", "headless data")
            val oldSettings = settings
            host.switchTo("native")
            assertFailsWith<IllegalStateException> { oldSettings.load() }
            assertTrue(settings.load() != saved)
            assertTrue(history.loadAll().isEmpty())
            host.switchTo("headless")
            assertEquals(saved, settings.load())
            assertEquals("headless data", history.loadAll().single().messages.single().content)
            assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                host.diagnostics().plugins.single { it.id == "provider.ui.compose" }.state)
            assertEquals(listOf(1L, 2L), repository.generations("headless"))
            host.close()
            val reopened = createDesktopKoogChatRuntime(homeDirectory = home, profile = hostProfile)
            try {
                assertEquals("headless", (reopened.owner as KcodeProfileHost).state.value.profileId)
                assertEquals(saved, settings.load())
                assertEquals("headless data", history.loadAll().single().messages.single().content)
            } finally { reopened.close() }
        } finally {
            host.close()
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedNativeTargetRestoresLockedPackagesAndTheBorrowedHostInputs(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-native-live-recovery")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        var live = 0
        val capture = kcodePlugin(PluginDescriptor("provider.conversation-overlay.platform", "test", "test", emptySet()),
            plugin<String>(validator = ConfigValidator { it }, inject = dependencies(KcodeSettings.Key, KcodeHistory.Key)) { _, config ->
                live++
                collect { live-- }
                check(config != "fail") { "native target allocation refused" }
            }, "okay")
        val host = createDesktopProfileHost(homeDirectory = home, profile = KcodePluginProfile(overrides = listOf(capture)))
        try {
            val baseline = assertNotNull(repository.loadCommitted("native"))
            val broken = baseline.definition.copy(id = "broken", dataScope = ProfileDataScope(), patches = baseline.definition.patches +
                ProfileOperation.Configure("provider.conversation-overlay.platform", JsonPrimitive("fail"), "string"))
            repository.saveDraft(broken)
            val before = repository.state()
            assertFailsWith<IllegalStateException> { host.switchTo("broken") }
            assertEquals(before, repository.state())
            assertEquals(baseline, repository.loadCommitted("native"))
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(1, live)
            assertTrue(host.runtime.applicationContent!!.modelCatalog().providers.isNotEmpty())
            assertTrue(host.runtime.pluginManager!!.installed().any { it.id == "provider.history.platform" })
        } finally {
            host.close()
            assertEquals(0, live)
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun shippedFactoryBootsDefaultAndSelectedHeadlessProfilesWithScopedStorage(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-native-profile-host")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        lateinit var settings: AppSettingsStore
        lateinit var history: ConversationHistoryRepository
        val capture = kcodePlugin(PluginDescriptor("provider.conversation-overlay.platform", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeSettings.Key, KcodeHistory.Key)) { context, _ ->
                settings = context.require(KcodeSettings.Key).store
                history = context.require(KcodeHistory.Key).repository
            }, Unit)
        val hostProfile = KcodePluginProfile(overrides = listOf(capture))
        try {
            val native = createDesktopKoogChatRuntime(homeDirectory = home, profile = hostProfile)
            try {
                val runtime = native.owner as KcodeProfileHost
                assertTrue(runtime.modelCatalog().providers.isNotEmpty())
                assertTrue(native.pluginManager!!.installed().any { it.id == "provider.ui.compose" })
                assertNotNull(repository.loadCommitted("native"))
            } finally { native.close() }
            val baseline = assertNotNull(repository.loadCommitted("native"))
            assertEquals(listOf("kcode.base", "kcode.agent", "kcode.default-ui"), baseline.definition.bundles.map { it.id })
            assertEquals(3, baseline.bundles.size)
            val headless = baseline.definition.copy(id = "headless", displayName = "Headless",
                dataScope = ProfileDataScope(),
                patches = baseline.definition.patches + ProfileOperation.Disable("provider.ui.compose"))
            repository.saveDraft(headless)
            val custom = createDesktopKoogChatRuntime(homeDirectory = home, profileId = headless.id, profile = hostProfile)
            var savedSettings = ai.meteor.kcode.settings.StoredAppSettings()
            try {
                val runtime = custom.owner as KcodeProfileHost
                assertTrue(runtime.modelCatalog().providers.isNotEmpty())
                assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                    runtime.diagnostics().plugins.single { it.id == "provider.ui.compose" }.state)
                savedSettings = runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))).settings
                assertEquals(savedSettings, settings.load())
                history.appendMessage(1, "Scoped history", 1, "User", "headless data")
            } finally { custom.close() }
            assertTrue(Files.exists(home.resolve("profile-data/profile-headless/settings.preferences_pb")))
            assertTrue(Files.exists(home.resolve("profile-data/profile-headless/history.db")))
            assertEquals(headless, repository.loadCommitted("headless")?.definition)
            repository.select("headless")
            repository.saveDraft(headless.copy(patches = listOf(ProfileOperation.Disable("missing"))))
            val restarted = createDesktopKoogChatRuntime(homeDirectory = home, profile = hostProfile)
            try {
                val runtime = restarted.owner as KcodeProfileHost
                assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                    runtime.diagnostics().plugins.single { it.id == "provider.ui.compose" }.state)
                assertEquals(savedSettings, settings.load())
                assertEquals("headless data", history.loadAll().single().messages.single().content)
            } finally { restarted.close() }
            val original = createDesktopKoogChatRuntime(homeDirectory = home, profileId = "native", profile = hostProfile)
            try {
                assertTrue(settings.load() != savedSettings)
                assertTrue(history.loadAll().isEmpty())
            } finally { original.close() }
            assertEquals(baseline.lock, repository.loadCommitted("native")?.lock)
        } finally { home.toFile().deleteRecursively() }
    }
}
