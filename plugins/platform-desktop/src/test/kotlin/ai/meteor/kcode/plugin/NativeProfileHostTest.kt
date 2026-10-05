package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopKoogChatRuntime
import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
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
