# Scheduling service and consumers

`provider.schedules.history` consumes `KcodeHistory` and provides `KcodeSchedules` / `ScheduledTaskCoordinator`. HistoryScheduledTaskCoordinator, ConversationScheduledTaskSession and repeat-after-dispatch calculations belong to this module. UI receives the current coordinator through the committed application view; it never constructs the history implementation.

`consumer.tools.schedule` contributes model tools independently. The unavailable service produces no task sessions and does not dispatch tasks. `plugins:schedule-dispatch` owns application dispatch orchestration as a separately removable consumer. The host provides the system notification bridge. Tests cover task validation, lifecycle, due dispatch, repeat advancement and provider dependency changes.

The provider owns all suspending operations, including dispatcher loops and retained
session calls. Disposal cancels and joins their cleanup before returning. Factories,
coordinators and sessions retained from a disposed generation reject further access;
re-enabling or replacing history creates a fresh generation. Tests block repository
operations, verify teardown waits for their cancellation cleanup, and verify stale
handles both directly and through Cordis dependency rebinding.
