package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopKoogChatRuntime
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.profiles.ProfileOperation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.history.ConversationHistoryRepository
import org.cordis.dependencies
import org.cordis.plugin

class NativeProfileHostTest {
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
                val runtime = native.owner as KcodePluginRuntime
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
                val runtime = custom.owner as KcodePluginRuntime
                assertTrue(runtime.modelCatalog().providers.isNotEmpty())
                assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                    runtime.inventory.snapshot().single { it.id == "provider.ui.compose" }.state)
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
                val runtime = restarted.owner as KcodePluginRuntime
                assertEquals(ai.meteor.kcode.plugin.api.PluginState.Disabled,
                    runtime.inventory.snapshot().single { it.id == "provider.ui.compose" }.state)
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
