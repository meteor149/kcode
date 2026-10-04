package ai.meteor.kcode.chat

/** Notification capability supplied by a mounted provider. Calls are revoked on withdrawal. */
interface ScheduledTaskPlatformHost {
    suspend fun isAppInForeground(): Boolean

    suspend fun showTriggeredNotification(title: String, body: String)
}
