package ai.meteor.kcode

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.random.Random

internal object IosScheduledTaskPlatformHost : ScheduledTaskPlatformHost {
    fun requestAuthorization() {
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
            ) { _, _ -> }
    }

    override fun isAppInForeground(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    override fun showTriggeredNotification(title: String, body: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "standalone-task-${Random.nextLong()}",
            content = content,
            trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { _ -> }
    }
}
