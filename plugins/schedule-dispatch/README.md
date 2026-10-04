# Application scheduling consumer

`consumer.schedules.application` consumes `KcodeSessions`, `KcodeSchedules` and `KcodeUiSlots`. Its application effect owns due-task execution, standalone result publication and lifecycle-aware notifications. MainPage only renders committed effect registrations.

Effect registration is reversible. Unmounting the contribution cancels its dispatch coroutine; remounting starts a new one. The initial loaded value is captured in the effect key and body to avoid a duplicate startup loop. Localization is resolved from the explicit request language when a task is due, without layout CompositionLocals. The host supplies the notification bridge; message execution still uses shared orchestration primitives pending its own service extraction.

PluginCompositionTest runs the actual effect in a Compose recomposer and verifies single startup, cancellation, re-enable and absence of duplicate runners.

Dispatch requires `KcodeScheduledTaskNotifications` and captures its revocable service
within its plugin effect. Notification implementations are not UI request inputs.
Removing notifications makes this consumer Pending; remount restores the effect.
