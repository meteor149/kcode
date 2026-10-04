package ai.meteor.kcode.plugin.notifications

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsFactory
import ai.meteor.kcode.plugin.api.ScheduledTaskNotificationsResource
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun androidScheduledTaskNotificationsFactory(activity: Activity, channelName: String) =
    nativeNotificationsFactory({ activity }, channelName)

fun androidScheduledTaskNotificationsFactory(inputs: AndroidPluginHostInputs, channelName: String) =
    nativeNotificationsFactory(inputs::activity, channelName)

private fun nativeNotificationsFactory(activityProvider: () -> Activity, channelName: String) = ScheduledTaskNotificationsFactory {
    require(channelName.isNotBlank()) { "Notification channel name is blank" }
    val host = AndroidScheduledTaskNotifications(
        context = activityProvider().applicationContext,
        channelName = channelName,
        isForeground = { (activityProvider() as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true },
        launchIntent = {
            val activity = activityProvider()
            Intent(activity.applicationContext, activity.javaClass)
        },
    )
    ScheduledTaskNotificationsResource(host, host::close)
}

internal class AndroidScheduledTaskNotifications(
    private val context: Context,
    channelName: String,
    private val isForeground: () -> Boolean,
    private val launchIntent: () -> Intent,
) : ScheduledTaskPlatformHost {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notificationTag = "$ChannelId.${UUID.randomUUID()}"
    private val ownedIds = mutableSetOf<Int>()
    private var nextId = StandaloneNotificationId
    private var closed = false

    init {
        manager.createNotificationChannel(NotificationChannel(
            ChannelId, channelName, NotificationManager.IMPORTANCE_DEFAULT,
        ))
    }

    override suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Notifications are closed" }
        isForeground()
    }

    override suspend fun showTriggeredNotification(title: String, body: String) = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Notifications are closed" }
        val intent = launchIntent().apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val notification = Notification.Builder(context, ChannelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val id = nextId++
        // Permission denial keeps scheduled execution successful, as before.
        runCatching { manager.notify(notificationTag, id, notification); ownedIds += id }
        Unit
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        if (!closed) {
            closed = true
            val failures = ownedIds.mapNotNull { id -> runCatching { manager.cancel(notificationTag, id) }.exceptionOrNull() }
            ownedIds.clear()
            if (failures.isNotEmpty()) throw PluginCleanupException("Android notifications", failures)
        }
    }

    private companion object {
        const val ChannelId = "standalone_scheduled_tasks"
        const val StandaloneNotificationId = 4_200
    }
}
