# Goal service and consumers

`provider.goal-sessions.history` consumes `KcodeHistory` and provides `KcodeGoals` / `GoalSessionFactory`. ConversationGoalSession is private to this module. All UI actions and agent tools receive the same cached session instance for each conversation, with shared mutation serialization. Repository publication precedes changes to the observable conversation; failed storage writes leave the committed goal intact.

`consumer.tools.goal` contributes model tools, and `provider.continuation.goal` contributes goal continuation policy. These are independent Fiber mounts. Missing session providers supply no model goal tools; restored-goal execution waits for an available session. Tests cover lifecycle, token budgets, failed persistence, provider enable/disable and history rebinding.

`consumer.commands.goal` separately contributes `/goal` grammar and command handling to
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
