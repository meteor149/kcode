package ai.meteor.kcode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class AndroidScheduledTaskPlatformHost(
    private val context: Context,
) : ScheduledTaskPlatformHost {
    private val foreground = AtomicBoolean(false)
    private val notificationIds = AtomicInteger(StandaloneNotificationId)

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                context.getString(R.string.scheduled_task_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun setForeground(value: Boolean) {
        foreground.set(value)
    }

    override fun isAppInForeground(): Boolean = foreground.get()

    override fun showTriggeredNotification(title: String, body: String) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, ChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(notificationIds.getAndIncrement(), notification)
        }
    }

    private companion object {
        const val ChannelId = "standalone_scheduled_tasks"
        const val StandaloneNotificationId = 4_200
    }
}
