# Schedule feature

`feature.schedule` is one dual-target install/enable boundary for persistent one-time and
recurring tasks, model tools, completion tools and headless due-task execution. Its entry
is `ai.meteor.kcode.plugin.ScheduleFeaturePlugin` with `Unit` configuration. The internal
provider consumes `KcodeHistory`; tools consume `KcodeTools` and `KcodeSchedules`; dispatch
consumes session, conversation execution, settings/model policy, LLM registry, agent and
generation contracts. Missing required services suspend the corresponding children.

Dispatch runs within the feature lifetime, without rendering, default UI slots or Markdown.
Each due task reads committed model settings and publishes through the shared session data
plane, visible to existing and newly opened presentation leases. Localization, Goal sessions
and notifications have private reactive child dependencies; their absence keeps dispatch
available. Notification failure does not invalidate a durable result. Private XML-derived
English labels travel with the package and never use host resource fallback.

Withdrawal cancels and joins the dispatcher and owned generation cleanup, discards
unpublished standalone results, removes tools and rejects retained coordinator/session
calls. Re-enabling restores persisted tasks and creates a fresh generation. The notification
backend remains independent native infrastructure used by other generation capabilities.

The former `provider.schedules.history`, `consumer.tools.schedule` and
`consumer.schedules.application` IDs migrate to this feature. Legacy entry classes remain
available for retained external dependency graphs and explicit alternative compositions;
they are not separate default releases. The former `schedule-dispatch` Gradle module is
consolidated into this module.

Tests cover task validation, recurring advancement, due dispatch, owned cancellation and
stale handles. `ScheduleDispatchPrivateLifecycleTest` imports actual private JAR entries
and the complete aggregate, verifies execution without UI or optional providers, committed
settings, held cleanup and persisted task recovery. `GoalSchedulePrivateLoadingTest` checks
private provider and shared SDK identity. `PluginPackageIntegrationTest` verifies old
package graph retention and failed migration commit preservation. Corresponding Android
instrumentation sources use real APKs; compilation alone does not establish device execution.

API 59 dispatch localization consumes `TranslationCatalog.configuredLanguage` for its
committed snapshot. Missing/withdrawn optional localization falls back to private dispatch
labels without decoding a language scalar or requiring default UI services. No configuration
schema is owned by the dispatcher.
