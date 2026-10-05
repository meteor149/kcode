package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.profileui.ProfileUiSession
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class NativeProfileUiHostTest {
    @Test
    fun actualPackagedManagementContributionWithdrawsAndReturnsWithFreshState(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-profile-ui-host")
        lateinit var slots: KcodeUiSlots
        lateinit var client: ProfileManagementClient
        val capture = kcodePlugin(PluginDescriptor("provider.ui.compose", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeUiSlots.Key, KcodeProfiles.Key)) { ctx, _ ->
                slots = ctx.require(KcodeUiSlots.Key)
                client = ctx.require(KcodeProfiles.Key).client
            }, Unit)
        val host = createDesktopProfileHost(homeDirectory = home, profile = KcodePluginProfile(overrides = listOf(capture)))
        try {
            val section = slots.snapshot().settingsSections.single { it.id == "profiles" }
            assertTrue(host.pluginManager.installed().any { it.id == "provider.ui.settings.profiles" })
            val committed = FileProfileRepository(home.resolve("profiles").toFile()).loadCommitted("native")!!
            assertEquals("2", committed.bundles.single { it.id == "kcode.default-ui" }.version)
            val session = ProfileUiSession(client)
            try {
                session.refresh()
                assertEquals("native", session.state.value.target!!.profileId)
                session.clone("ui-copy", "UI copy")
                assertEquals("ui-copy", session.state.value.target!!.profileId)
                assertFalse(session.state.value.dirty)
                assertTrue(session.state.value.preview!!.packagesVerified)
                assertEquals("native", client.catalogue().activeProfileId)
            } finally { session.close() }
            host.pluginManager.setEnabled("provider.ui.settings.profiles", false)
            assertTrue(slots.snapshot().settingsSections.none { it.id == "profiles" })
            host.pluginManager.setEnabled("provider.ui.settings.profiles", true)
            val restored = slots.snapshot().settingsSections.single { it.id == "profiles" }
            assertNotSame(section, restored)
        } finally {
            host.close()
            check(home.toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            home.toFile().deleteRecursively()
        }
    }
}
