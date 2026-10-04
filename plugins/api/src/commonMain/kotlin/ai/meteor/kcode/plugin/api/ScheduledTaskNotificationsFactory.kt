package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.chat.ScheduledTaskPlatformHost

/** Per-mount native notification resources. Withdrawal joins calls before release. */
fun interface ScheduledTaskNotificationsFactory {
    suspend fun create(): ScheduledTaskNotificationsResource
}

class ScheduledTaskNotificationsResource(
    val host: ScheduledTaskPlatformHost,
    val close: suspend () -> Unit,
)
