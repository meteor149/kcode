package ai.meteor.kcode.chat

/** Narrow platform bridge for lifecycle-aware scheduled-task notifications. */
interface ScheduledTaskPlatformHost {
    fun isAppInForeground(): Boolean

    fun showTriggeredNotification(title: String, body: String)
}

object ForegroundScheduledTaskPlatformHost : ScheduledTaskPlatformHost {
    override fun isAppInForeground(): Boolean = true

    override fun showTriggeredNotification(title: String, body: String) = Unit
}
