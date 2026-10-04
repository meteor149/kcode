package ai.meteor.kcode.plugin

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ScheduledTask
import ai.meteor.kcode.history.ScheduledTaskStatus
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.cordis.dependencies
import org.cordis.plugin

class EphemeralProviderCompositionTest {
    @Test
    fun runtimesAndProviderGenerationsOwnSeparateSettingsMessagesAndTasks() = runTest {
        lateinit var firstHistory: ConversationHistoryRepository
        lateinit var secondHistory: ConversationHistoryRepository
        lateinit var firstSettings: AppSettingsStore
        lateinit var secondSettings: AppSettingsStore
        val first = KcodePluginRuntime.create(config(capture { history, settings -> firstHistory = history; firstSettings = settings }))
        val second = KcodePluginRuntime.create(config(capture { history, settings -> secondHistory = history; secondSettings = settings }))
        try {
            firstHistory.appendMessage(1, "first", 1, "User", "first message")
            firstHistory.upsertScheduledTask("first", ScheduledTask("t", 1, "first task", "work", ScheduledTaskStatus.Paused, 10, createdAt = 1, updatedAt = 1))
            first.updateSettings(SettingsUpdate(searchProvider = "exa"))
            assertEquals("first message", firstHistory.loadAll().single().messages.single().content)
            assertEquals(1, firstHistory.loadScheduledTasks().size)
            assertTrue(secondHistory.loadAll().isEmpty())
            assertTrue(secondHistory.loadScheduledTasks().isEmpty())
            assertEquals("google", secondSettings.load().webSearchProvider)
            secondHistory.appendMessage(1, "second", 1, "User", "second message")
            val previousHistory = firstHistory
            val previousSettings = firstSettings
            first.pluginManager.setEnabled("provider.history.platform", false)
            first.pluginManager.setEnabled("provider.settings.platform", false)
            assertFailsWith<IllegalStateException> { previousHistory.loadAll() }
            assertFailsWith<IllegalStateException> { previousSettings.load() }
            first.pluginManager.setEnabled("provider.history.platform", true)
            first.pluginManager.setEnabled("provider.settings.platform", true)
            assertTrue(firstHistory.loadAll().isEmpty())
            assertTrue(firstHistory.loadScheduledTasks().isEmpty())
            assertEquals("google", firstSettings.load().webSearchProvider)
            assertEquals("second message", secondHistory.loadAll().single().messages.single().content)
        } finally { first.close(); second.close() }
    }

    private fun capture(bind: (ConversationHistoryRepository, AppSettingsStore) -> Unit) = kcodePlugin(
        PluginDescriptor("test.ephemeral", "test", "test", emptySet()),
        plugin<Unit>(name = "capture-ephemeral", inject = dependencies(KcodeHistory.Key, KcodeSettings.Key)) { ctx, _ ->
            bind(ctx.require(KcodeHistory.Key).repository, ctx.require(KcodeSettings.Key).store)
        }, Unit,
    )

    private fun config(capture: KcodePluginMount) = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
        featurePlugins = listOf(capture),
    )
}
