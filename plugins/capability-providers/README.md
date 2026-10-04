# Platform execution providers

This module adapts platform filesystem, system shell and optional Ubuntu shell
implementations to the neutral `fs`, `shell` and `ubuntuShell` contracts. Consumers
never receive the platform path or executor types. Workspace containment and privilege
selection remain with the platform backend.

Each mount owns a `PluginOperationOwner`. Withdrawal cancels/joins pending IO and
shell calls, including delegate cleanup, and previously published adapters reject
subsequent access. Synchronous filesystem path operations also check that the provider
is live. Re-enabling publishes a new adapter and rebinds consumers.

Returned Source/Sink streams transfer ownership to the caller as specified by
`FileSystemBackend`; callers must close them. They are not a second hidden service
owned by the provider. Stream factory calls themselves belong to the provider.
Bounded stream acquisition cannot lose a handle at a cancellation/dispatch boundary:
failed handoff closes the acquired stream, and withdrawal waits for discarded cleanup
using a completion token independent of the cancelled operation Job.

Cordis tests cover suspended IO/process cleanup, stale sync/async filesystem entry
points, system and Ubuntu execution, fresh adapters after re-enable, tool dependency
withdrawal, provider replacement and workspace containment. Desktop native cancellation is verified with a real sleeping Java child launched
through the shell, including executable/workspace paths requiring Windows quoting.
The interruptible wait exits on cancellation, and cleanup joins the process tree.
Android privileged/Ubuntu process cancellation still needs its platform-specific
verification; an adapter cleanup test does not prove a Binder call is cancellable.

Native Android settings-aware Shell providers declare `KcodeSettings` dependencies.
Each mount constructs its platform executor and supplies a revocable mode reader that
loads the currently committed settings for each command. Settings replacement remounts
both worlds; withdrawal makes them Pending, cancels/joins calls and revokes retained
executors/readers. Android Main uses this path without a separate AtomicReference or UI
mode callback. Explicit external-mode callers can retain the generic provider path.
Private APK providers and actual native App UID commands are tested independently;
mode selection tests do not prove privileged authorization or Ubuntu installation.
