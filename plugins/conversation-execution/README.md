# Conversation execution provider

`provider.conversation-execution.history` publishes the shared `ConversationExecution`
contract through the `conversationExecution` service. It depends on `history` and
`conversationCommands` and owns message sending, command dispatch, regeneration, and generic
response execution. Goal restoration and scheduled dispatch prepare requests in their
own plugins. Compose pages supply inputs and callbacks through this contract.

The default chat page and schedule dispatch consumer declare this service as a
dependency. Disabling it withdraws their contributions; enabling it creates a new
executor. Disposal cancels and joins tracked generation and owned coroutine work.
Calls through an executor retained after disposal fail instead of starting new work.

History writes before a model request must succeed before the request starts. Tests
cover storage failure, cancellation, stale services, and Cordis consumer rebinding.

`core.conversation-commands` owns a reversible command registry. Grammar, generation-time
availability, localization, and domain decisions belong to individual contributions.
The registry cancels and joins each contribution's active command operations on disposal
and rejects resolved handles retained afterward. Its committed snapshot is shared by
the executor and Compose input controls, after installation persistence succeeds.

Command feedback uses an atomic history batch and publishes the transcript after commit.
Storage failures appear as transient execution failures instead of unsaved transcript
messages. IDs are reserved before suspension so command feedback and generation events
cannot overwrite each other. Caller operations expire when the command request ends.
Room tests inject a failure on the second message and prove rollback of both messages
and conversation creation. Android tests load a real APK command and dispatch it through
the host's executor, then disable, enable, and uninstall it.

Ordinary sends publish the user message only after its history commit. Setup feedback
uses an atomic batch, and regeneration retains the committed transcript until deletion
succeeds. Final response commit failures report an incomplete execution and remove the
uncommitted assistant projection; scheduled dispatch cannot reveal it as a completed run.
Partial and error replies must also commit before they remain in the transcript. Storage
failures use a transient, credential-redacted notice. Disposal waits for cancellation
cleanup and scheduled completion callbacks, including their suspending history operations.

`startResponse` accepts a feature-prepared prompt, optional durable user input, capability
sessions, and a lifecycle observer. It has no Goal status or scheduled-task decisions.
The Goal provider supplies cancellation/failure transitions and continuation prompts;
restoration conditions belong to Goal UI, while scheduled prompts, completion channels,
visibility and notifications belong to scheduled dispatch. Model orchestration asks the
Goal session for a continuation instead of embedding its prompt or active-status policy.
Other provider lifecycle boundaries remain part of the broader plugin audit.


`provider.generation` owns the generation runner and its coroutine scope. Application
views receive it through `ApplicationViewServices.generationRunner`; there is no implicit
Compose-created runner in the default renderer. Withdrawing this provider cancels and
joins active responses, revokes captured runners, and suspends the default UI and native
foreground policy consumers. Its `activeTasks` flow is a read-only activity projection,
not a persisted session log. Retiring or mutating a runtime from a generation task,
including NonCancellable task cleanup after service withdrawal, rejects before admission.

The actual module JAR test covers cancellation/join, waiting for task cleanup, stale
runner rejection, reenabling, persisted restore, uninstall, and runtime-mutation/close
reentry. Explicit `ApplicationHostOptions.generationRunner` overrides remain available
for custom compositions, whose caller owns their runner and background policy.
