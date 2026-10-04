# In-process subagent provider

`provider.subagents.in-process` supplies the `subagents` factory service. The coordinator owns agent jobs, mailboxes, delegation, status and shutdown. `SubagentCoordinator` exposes coordination operations, with no Koog ToolRegistry on its domain API. Each turn owns and shuts down its coordinator.

The model-facing tools live in `plugins:subagents`; their Consumer builds schemas from the current turn's coordinator. The loop receives only the coordinator factory and turn contracts. The default implementation retains five-agent concurrency and existing inherited-context and mailbox behavior.

External process/ACP providers and per-agent Cordis isolate realms remain deferred. State/concurrency tests live in this module; tool surface tests live in the Consumer module.

The mounted factory owns every coordinator and a supervisor job for that session's
child agents. Withdrawal atomically invalidates the factory, cancels/joins coordinator
calls, and cancels/joins child work under non-cancellable cleanup. Existing factories
and coordinators reject further use. Normal turn shutdown invalidates only that
coordinator and releases it from the factory; later turns may create new sessions.
A child agent cannot synchronously dispose its own coordinator. Default coordinator
shutdown also joins agent jobs, rather than only signalling cancellation.

Factory lifecycle tests hold a child's cleanup suspended and prove disposal waits;
Cordis composition tests verify old handle rejection while disabling, loop dependency
Pending state, and a fresh factory after re-enable.


Capacity is provider-owned. Unit configuration retains five total slots; JSON
`{"maxConcurrency":2}` selects a positive integer between 1 and 64, including the root.
Unknown fields, strings, fractional values and unsupported limits are rejected before
replacement. The factory publishes nullable `maxConcurrency` metadata; retirement clears
it. The loop derives both root and child instructions from the mounted factory instead of
an SDK constant. A provider that omits this metadata does not acquire an invented limit.
