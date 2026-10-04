package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.KcodeScheduledTaskNotifications
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsResource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

object ScheduledTaskNotificationsProviderPlugin : Plugin<ScheduledTaskNotificationsFactory> {
    override val name = "scheduled-task-notifications"

    override suspend fun apply(ctx: Context, config: ScheduledTaskNotificationsFactory, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val resource = withContext(NonCancellable) {
            owner.run { config.create() }.also { resource ->
                effect.collect {
                    owner.requireCanClose()
                    withContext(NonCancellable) {
                        val failures = mutableListOf<Throwable>()
                        runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
                        runCatching { resource.close() }.exceptionOrNull()?.let(failures::add)
                        if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
                    }
                }
            }
        }
        if (!effect.isActive) return
        KcodeScheduledTaskNotifications(ctx, object : ScheduledTaskPlatformHost {
            override suspend fun isAppInForeground() = owner.run { resource.host.isAppInForeground() }
            override suspend fun showTriggeredNotification(title: String, body: String) =
                owner.run { resource.host.showTriggeredNotification(title, body) }
        })
    }
}

/** Explicit headless/default composition with no OS notifications. */
fun foregroundOnlyNotificationsFactory() = ScheduledTaskNotificationsFactory {
    ScheduledTaskNotificationsResource(object : ScheduledTaskPlatformHost {
        override suspend fun isAppInForeground() = true
        override suspend fun showTriggeredNotification(title: String, body: String) = Unit
    }) {}
}
