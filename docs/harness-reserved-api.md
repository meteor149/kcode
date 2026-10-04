# Harness reserved subsystem APIs

The plugin architecture covers existing features; key unimplemented Harness subsystems have
specification contracts reserved for future providers. This document describes
`ai.meteor.kcode.plugin.api.harness` in `plugins:api`. These definitions **are not available
features**. The Native bundle does not mount jobs, subprocess, terminals, or sessionPersistence
providers. Advanced capabilities on current Session/FS providers are `null`; consumers must
check capability availability or wait for an actual provider.

The reference is the local `deepseek-harness` repository at commit
`47f943859bef60e4160492346772ded9b24f765a`: `packages/jobs/jobs/src/{index,types}.ts`,
`packages/subprocess/subprocess/src/{index,types}.ts`,
`packages/terminal/terminal/src/{index,types}.ts`,
`packages/core/session/src/{index,types,surface,preparation}.ts`,
`packages/session/session-persistence/src/index.ts`, and `packages/fs/fs/src/{index,types}.ts`.
This is a Kotlin type/lifecycle mapping. It does not promise that current Room conversation
data can be read directly as a Harness log.

## Services and layers

| Harness | Kotlin definition/entry point | Current availability |
| --- | --- | --- |
| `ctx.jobs` | `KcodeJobs.Key` / `HarnessJobRegistry` | Reserved only; distinct from scheduled tasks in `schedules`. |
| `ctx.subprocess` | `KcodeSubprocess.Key` / `HarnessSubprocessRuntime` | Reserved only; the current one-shot `shell` is not presented as a process-handle service. |
| `ctx.terminals` | `KcodeTerminals.Key` / `HarnessTerminalSessionService` | Reserved only; a real PTY backend requires a separate implementation. |
| `ctx.sessions` | `KcodeSessions.eventStore` / `HarnessSessionStore` | Optional reserved capability; the existing `factory` continues to provide session UI projections. |
| `ctx.sessionPersistence` | `KcodeSessionPersistence.Key` / `HarnessSessionPersistence` | Reserved only; separate from the conversation-row `history` repository. |
| Full `ctx.fs` | `KcodeFileSystem.harness` / `HarnessFileSystem` | Optional reserved capability; `backend` continues to provide ordinary file capabilities for current tools. |
| Three `fs` policy events | `KcodeFileSystem.observations` / `HarnessFileObservationEvents` | Optional typed gate, not a built-in observation policy. |

Advanced Session/FS implementations must share the execution world and data source of the
same wrapper's basic factory/backend. New capabilities must not silently introduce different
session or path semantics. Providers collect `close()` cleanup in their own Cordis effects;
registrants collect every returned `Disposable` in their effects. Exporting these classes
does not install services. Dynamic plugins use the shared `plugin.api` namespace to preserve
ABI type identity.

## Identity, ownership, and cancellation

`HarnessAgentOwner` represents the exact active object in a future agent runtime. Implementing
the interface, copying `agentId`/`sessionId`, or guessing a resource ID does not grant authority.
Providers must validate `owner === the currently registered instance`. This is not an alias
for the current `ChatService`. The lifecycle must await owner disposing registrations and
report their failures. The caller's kernel/composition context supplies scope; it must not
be inferred from headers, session names, or paths.

Jobs check the exact live owner at start and isolate access by that owner's SessionId.
Ownerless jobs are available to all callers. Every terminal operation is isolated by exact
owner object; another agent with the same session ID cannot share it. Background registrations,
unpublished allocations, and failed cleanup all count as owner activity.

`HarnessCancellationSignal` maps AbortSignal, with the original `Throwable` as the cancellation
reason. Listener registration and cancellation must be atomic. Notify immediately when
already cancelled and never rewrite the reason. Ordinary suspending waits use coroutine
cancellation. `done()` replaces a read-only JS Promise: waiters are independent, and cancelling
a wait does not cancel the promise or resource. Explicit `cancel`/`terminate`/signal operations
control underlying resource cancellation.

## Jobs

`HarnessJobKind` is an open namespace; the registry mints `HarnessJobId` identities. Before
running the producer, start checks controller scope, owner cleanup, parameters, and admission.
It invokes `run()` once. If that throws, the producer cleans partial resources and no
registration remains. Once hooks return, successful publication must not pass through another
fallible or cancellable step.

State progresses from Running through optional Stopping to Completed/Killed/Failed. The first
terminal state wins. `kill` first invokes synchronous, idempotent producer cancellation and
commits Stopping/reported only on success. A cancellation throw leaves state unchanged.
`done()` returns after producer resources are released; rejection becomes Failed, but a
cancellation throw must not be treated as proof that resources stopped.

get/list return fresh snapshots. Stream read uses one consumption cursor; final-output read
is idempotent in terminal state. outputLimitBytes limits the producer/controller's complete
model-visible output. The registry neither rewrites it nor supplies defaults. A positive
wait timeout returns the current snapshot; cancellation ends only the wait. Delivery of an
already committed terminal state wins a race with wait cancellation.

The done notification fires once after terminal-state commit and other observers. Listener
failures are contained and listeners are not awaited. changed notifications follow changes
to the owner's visible set: registration, Stopping, terminal states, owner removal, and
service clearing. Ownerless changes affect every observer. changed does not mark reported
and is not a superset of done. Controller/listener scope determines the owner they serve;
their respective Fibers revoke registrations.

## Subprocess and real PTY primitives

Ordinary specs explicitly supply argv/cwd/stdio/grace/env. argv does not pass through a shell;
consumers that need one supply shell argv themselves. Paths, PATH lookup, and fs belong to
the same execution world. stdio Pipe provides suspending raw byte sources/sinks, while the
consumer owns protocol framing. Collect retains a bounded tail and may include a bounded
complete spill. readFrom uses a caller-owned byte offset into the full stream without
consuming other readers' data. Lost windows report lossy; exceeding spill limits must not
be reported as complete-stream retention.

done supplies only exit code/signal, not output or timeout/cancellation classification.
Ordinary terminate starts tree-wide TERM → explicit grace → KILL; waitForExit waits for the
entire tree. An ordinary spec signal triggers the same termination sequence. Service unload
terminates and awaits all managed processes.

spawnTerminal is a separate real PTY primitive, not simulated with ordinary pipes. Its spec
signal cancels only unpublished allocations; published handles own their lifetime. Output
is UTF-8 bytes and ends only after queued output drains following top-level process exit.
An active transport failure rejects done. The substrate performs writes, foreground checks,
and signal delivery. PTY terminate waits for the complete observable session membership and
in-flight handle calls. Provider documentation must state platform observation limits rather
than equating top-level PID exit with quiescence of all resources.

Public `scrubHarnessParentEnvironment` supplies pure policy only: it removes credential-shaped
and ambient DSH_ names case-insensitively, preserving PATH/HOME/locale/proxy. Explicit environment
overrides apply after scrubbing; null values remove ordinary-process variables. Explicitly
forwarding credentials or DSH_ variables is the caller's decision. Tool deadlines, reason
classification, teardown ladders, and output presentation belong to consumers.

## Terminal registry and backend

Stable backend type registrations are revocable. spawn supplies a registry-minted ID, exact
owner, and combined setup cancellation. Unpublished failures/cancellation must roll back;
partial cleanup failures preserve both spawnFailure and cleanupFailure. After setup, the
registry rechecks owner/service liveness and publishes atomically. Owner-local names are
presentation metadata only.

A session has at most one active send; read/signal may observe it. send waitReason
(stdin_read/inferred_idle/timeout/session_exit) is independent of top-level sessionStatus.
A foreground command returning control does not imply that the top-level PTY exited.

kill/disposal returns only after successful backend close and quiescence of the captured
process tree. Failed cleanup remains owner activity for later disposing to consume and
report. Failed close clears its matching fence for retry, never a newer close attempt's
fence. Caller cancellation preserves the original reason. Lifecycle-triggered rollback
failure rejects both disposing and the related pending spawn. Stable TerminalErrorCode
wireValue values match the reference source.

## Session logs, surfaces, and persistence

`HarnessSessionEvents` defines core typed payloads. External plugins add log-only types through
`HarnessSessionEventType<T>` and serializers. Messages retain stable IDs, exact content blocks,
and open sources. Assistant sources require provider/model and retain adapter replay state.
tool/result is a single correlated tool-result block with user role. The full model-visible
request must be reconstructed from the event log and latest request/header. Log-only records
such as todo/request/context do not enter the message surface. Producers must not append
constructor-owned session/end-seed events themselves.

Append must read, validate, copy, and freeze the durable graph once. Reject JSON that cannot
round-trip losslessly, including non-finite numbers and negative zero, and invalid marker/source
references. Sequence numbers are contiguous and include raw chunks. The three message-producing
types require surface intent; other types forbid it. Replacement start/end is an inclusive
range of current surface nodes, and sourceEventSeqs covers every replaced node. Tool-result
replacement changes only content. derive consumes only the accepted surface; rewrite changes
replaceGeneration. There is no raw-log fallback or regenerated message identity. Human
transcripts read append-origin events separately from the model surface.

`HarnessSessionEventRecord` is an in-memory Kotlin representation. Persistence must use
Harness canonical logical fields: `type/seq/time/data`, optional top-level `sourceEventSeqs`
and `surfaceOp`, and `ignorable` only when true. `surface.operation` maps to
`surfaceOp: 'append'` or `{ op: 'replace', start, end }`. Do not persist the nested Kotlin surface
object directly as a format-0 wire record. Omit absent or false ignorable values. Unknown
required events reject reconstruction; unknown ignorable events are preserved unchanged.
Core payload serializers supply typed data and do not replace backend validation of
relationships, JSON, roles/sources, or provenance.

Validate SessionHeader logical format version and backend physical schema separately.
Header ID matches session identity; times/sequence numbers are nonnegative safe integers;
metadata is a detached immutable snapshot. fork preserves an inclusive prefix through a
completed turn plus lineage/seed/depth/preset, rejecting boundaries within open turns.
Kotlin prepare returns a Preparation reservation owning an unpublished session. enter uses
that exact session, with scope tied to the captured calling Fiber. Competing prepare calls
may coexist; only one enter wins. detach removes only its own entry.

| Session event | Mode and commit semantics |
| --- | --- |
| session/created | Synchronous emit, the sole announcement edge. A synchronous throw vetoes entry and triggers paired rollback/disposed; detach during announcement is deferred. |
| session/disposed | Synchronous emit; only announced entries get the paired edge; listener failures are contained. |
| session/event | Synchronous post-commit emit. Capture the listener snapshot before commit; callback failures cannot undo append. |
| session/flush | Awaited parallel. Start every scoped listener, await all settlements, then report failures. Only the exact live entered object may invoke it. |

Persistence create may defer physical materialization. append succeeds only after a contiguous
batch becomes durable. For a cold, complete interrupted tail, load preserves committed events
and appends missing tool errors/step/end/turn/end. Only a torn final physical record may be
dropped. Live open turns receive no crash repair. inspect returns a logical snapshot only;
cold synthetic closers are not written to storage. readFrom returns a detached suffix of
the physical prefix without repair, synthetic closers, or preparation/cache publication.
prepare holds an exclusive unpublished object through source-qualified revision convergence.
close drains writes and releases reservations. Raw artifact support is an explicit capability;
if supported but not physically materialized, it returns null. locate neither reads nor
creates files and does not grant permission. Current Room history provides none of these
guarantees.

## Filesystem observation and version guards

The full FS contract includes resolve/processPath/fileUrl/contains, stat/lstat, whole/stream
text, complete bounded bytes, stable directory listings, and atomic write/edit. targetKey/version
are opaque tokens, not native paths. Opaque actors associate observed state by object identity,
not owner paths. Metadata probes do not read content; lstat does not follow the final symlink.
Backends own text decoding, binary/NUL checks, and atomic edit critical sections. Consumers/policy
own line windows, diff presentation, and read-before-edit rules.

An absent optional write intent means unconditional but still atomic mutation. CreateIfAbsent
requires publication without replacement; ReplaceIfVersion replaces according to the observed
version. edit checks guards before literal matching; a missing target produces FS_STALE_VERSION.
write/edit outcomes provide LF-normalized before/after text as the diff basis; providers do
not generate diffs. When prior write context is unavailable, before is null. Byte-limit
violations fail rather than truncate.

FS sandboxMode uses the canonical read-only/workspace-write/danger-full-access values.
write/edit may receive per-call policy containing workspaceRoot and optional SessionId.
Without policy, backend configuration applies. Sandbox backends enforce policy; bare backends
do not guarantee confinement. This reserves capability expression only, without implementing
new sandbox or authorization policy.

fs/write-intent and fs/edit-intent use a single-decision waterfall. next delegates; the terminal
default is a null unconditional guard; the first decision is not merged with peers.
fs/observed is a synchronous emit recorder: a throw fails the tool call and asynchronous work
is not awaited. Present(version) and Absent distinguish actual observations from no observation.
Without observation policy, the basic file service remains unconstrained. Current native
providers expose null `harness/observations`; unversioned writeBytes is not presented as a
guarded atomic mutation.

## Verification boundaries

API unit tests cover shared environment scrubbing, raw tool argument fidelity, and serializer
round trips for identified messages/providers/opaque replay state. Runtime tests verify that
consumers of the four reserved services remain Pending and current Sessions have no eventStore.
Android/Desktop compilation confirms that the contracts are usable. These checks do not prove
that future backend lifecycle, durability, PTY, or version guards are implemented. When providers
are added, verify them separately and add real execution tests.
