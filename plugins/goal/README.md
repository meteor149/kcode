# Goal service and consumers

`src/profile-export/feature.goal.json` permits explicit Unit configuration in portable
Profiles. Goal documents, callbacks and saved execution state are not Profile configuration.

`feature.goal` / `GoalFeaturePlugin` is the single install/enable boundary. Its owned
children provide sessions, commands, model tools, continuation policy, status decoration,
and restoration effects. Missing optional UI services suspend presentation children only.
Disabling the feature withdraws all contributions, cancels/joins owned calls, and preserves
stored goals. Re-enabling restores fresh facades and contributions. The former six package
IDs migrate to this feature. If a surviving external dependency needs any former member,
composition recovery retains the whole former Goal group and disables the aggregate by
default. Removing that dependency permits group migration on restart. There is no separate
Goal UI module or release.

`provider.goal-sessions.history` consumes `KcodeHistory` and provides `KcodeGoals` / `GoalSessionFactory`. ConversationGoalSession is private to this module. All UI actions and agent tools receive the same cached session instance for each conversation, with shared mutation serialization. Repository publication precedes changes to the observable conversation; failed storage writes leave the committed goal intact.

`GoalToolConsumerPlugin` contributes model tools, and `GoalContinuationPlugin` contributes goal continuation policy as internal Fibers. Both require the Goal capability. Missing session providers supply no model goal tools; restored-goal execution waits for an available session. Tests cover lifecycle, token budgets, failed persistence, provider enable/disable and history rebinding.

`GoalCommandConsumerPlugin` contributes `/goal` grammar and command handling to
`conversationCommands`. It depends on the Goal session provider. Shared chat execution
and input controls contain no Goal command parser or switch. Localization and status
decisions live in this module; the executor supplies generic feedback and response
operations. Feedback is committed as an atomic history batch before UI publication.
Commands are serialized per conversation, and pause/clear wait for the previous
generation to stop before changing the goal. Tests cover committed state before model
response, failed edits, cancellation before pause feedback, grammar withdrawal, and
provider dependency rebinding.

The provider owns all suspending operations, including dispatcher loops and retained
session calls. Disposal cancels and joins their cleanup before returning. Factories,
coordinators and sessions retained from a disposed generation reject further access;
re-enabling or replacing history creates a fresh generation. Tests block repository
operations, verify teardown waits for their cancellation cleanup, and verify stale
handles both directly and through Cordis dependency rebinding.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- feature.goal

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.


Goal decoration actions and automatic restoration capture root `KcodeExecution` when hosted
in a managed runtime. Admission precedes running-response cancellation, session allocation,
resume flags and status persistence. Cancellation joins the admitted operation's cleanup.
Rejected or cancelled response preparation retains resume intent; only accepted restoration
clears it. Bare Cordis contexts retain the existing provider-owned lifecycle without requiring
a product runtime service. No SDK ABI changes in this phase.
