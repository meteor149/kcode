# Subagent feature

`feature.subagents` / `SubagentFeaturePlugin` is the install and enable boundary for
the default in-process coordinator, model tools, continuation policy and optional status
presentation. Its internal children react independently to tools, continuation registries,
UI slots and localization availability. Ordinary
agent execution can continue while the entire feature is disabled.

The feature validates configuration before allocating a coordinator factory. Unit
configuration retains five total slots; JSON `{"maxConcurrency":2}` selects an integer
between 1 and 64, including the root. Unknown fields and unsupported limits fail before
replacement. Nullable factory capacity metadata is cleared on retirement.

The factory owns every coordinator and its child-agent jobs. Withdrawal invalidates
retained handles, cancels and joins coordinator calls and child cleanup, and removes
tools and continuation policy. Turn shutdown releases only that coordinator. A child
cannot synchronously dispose its own coordinator. The neutral SDK retains the factory
contract for alternative implementations; implementation classes remain private.

The former `provider.subagents.in-process`, `consumer.tools.subagent`, and
`provider.continuation.subagent` releases migrate together. Surviving external package
dependencies retain the old group and suppress aggregate activation until migration
is possible. Saved disable choices and failed-commit rollback follow composition policy.

Tests cover coordination, capacity, tool schemas, retained handles, joined child cleanup,
replacement failure, withdrawal/recovery, and real desktop JAR/Android APK identities.
Android test compilation alone does not establish device execution.

Running status cards and detail sheets live in `subagentui` here. The feature registers
one `subagents` conversation decoration using the generic `AboveComposer` anchor;
`ui-pages` owns placement/measurement and contains no Subagent filtering or status UI.
Latest updates by canonical path supersede earlier messages before running states are
selected. Markdown detail content uses the optional Markdown contract with a plain-text
fallback. Withdrawn presenters and renderers become quiet; UI service recovery creates
a fresh presenter while keeping coordinator/tools mounted throughout.

`SubagentUiPrivateLifecycleTest` imports the real aggregate JAR, verifies private presenter
and shared API 48 anchor identity, renders actual presenter preparation, checks latest
completed updates hide stale running states, and tests optional UI and whole-feature
withdrawal/recovery. The corresponding real APK test source compiles; source compilation
is not device execution evidence.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- feature.subagents

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.

feature.subagents also permits JSON maxConcurrency as an integer in 1..64.
