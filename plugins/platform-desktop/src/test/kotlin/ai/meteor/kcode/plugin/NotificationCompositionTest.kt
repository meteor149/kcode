package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsResource
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class NotificationCompositionTest {
    @Test
    fun disabledProviderAllocatesNothingAndDispatchRebindsOnRemount() = runTest {
        var opened = 0
        var closed = 0
        lateinit var current: ScheduledTaskPlatformHost
        val runtime = KcodePluginRuntime.create(config(ScheduledTaskNotificationsFactory {
            opened++
            ScheduledTaskNotificationsResource(QuietNotifications()) { closed++ }
        }) { current = it }.copy(profile = KcodePluginProfile(disabled = setOf(ProviderId))))
        try {
            assertEquals(0, opened)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.schedules.application" }.state)
            runtime.pluginManager.setEnabled(ProviderId, true)
            val previous = current
            assertTrue(current.isAppInForeground())
            runtime.pluginManager.setEnabled(ProviderId, false)
            assertEquals(1, closed)
            assertFailsWith<IllegalStateException> { previous.isAppInForeground() }
            runtime.pluginManager.setEnabled(ProviderId, true)
            assertNotSame(previous, current)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "consumer.schedules.application" }.state)
        } finally { runtime.close() }
        assertEquals(opened, closed)
    }

    @Test
    fun cancelledAssemblyReleasesAllocatedNotifications() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val creating = backgroundScope.async {
            KcodePluginRuntime.create(config(ScheduledTaskNotificationsFactory {
                entered.complete(Unit)
                release.await()
                ScheduledTaskNotificationsResource(QuietNotifications()) { closed = true }
            }))
        }
        entered.await()
        creating.cancel()
        release.complete(Unit)
        creating.join()
        assertTrue(creating.isCancelled)
        assertTrue(closed)
    }

    @Test
    fun withdrawalWaitsForNotificationCleanupBeforeReleasingResources() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        lateinit var current: ScheduledTaskPlatformHost
        val host = object : QuietNotifications() {
            override suspend fun showTriggeredNotification(title: String, body: String) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val runtime = KcodePluginRuntime.create(config(ScheduledTaskNotificationsFactory {
            ScheduledTaskNotificationsResource(host) { closed = true }
        }) { current = it })
        try {
            val notifying = backgroundScope.async { current.showTriggeredNotification("test", "body") }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled(ProviderId, false) }
            cleaning.await()
            assertFalse(closed)
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            notifying.join()
            assertTrue(notifying.isCancelled)
            assertTrue(closed)
        } finally { release.complete(Unit); runtime.close() }
    }

    private fun config(factory: ScheduledTaskNotificationsFactory, bind: ((ScheduledTaskPlatformHost) -> Unit)? = null) =
        KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            scheduledTaskNotificationsFactory = factory,
            featurePlugins = listOfNotNull(bind?.let { capture ->
                kcodePlugin(PluginDescriptor("test.capture-notifications", "test", "test", emptySet()), plugin<Unit>(
                    name = "capture-notifications", inject = dependencies(KcodeScheduledTaskNotifications.Key),
                ) { ctx, _ -> capture(ctx.require(KcodeScheduledTaskNotifications.Key).host) }, Unit)
            }),
        )

    private open class QuietNotifications : ScheduledTaskPlatformHost {
        override suspend fun isAppInForeground() = true
        override suspend fun showTriggeredNotification(title: String, body: String) = Unit
    }

    private companion object { const val ProviderId = "provider.notifications.platform" }
}
