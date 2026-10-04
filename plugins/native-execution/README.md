# Native execution implementations

This module owns desktop foreground process execution and Android system/Ubuntu worlds:
identity routing, workspace resolution, PRoot installation, archive validation, process
group cancellation, output draining and privileged UserService request lifecycle.
Android native entries lease deployment inputs; settings-aware entries use Unit
configuration and declare the settings service dependency. Their effect owns command
calls and revokes/joins them before withdrawal. Custom compositions can supply a mode
reader without capturing an Activity in the native provider. Native resources terminate with
the operation, including NonCancellable cleanup. Settings-aware providers recreate executors
when their settings generation changes.

Shared retains the neutral AgentShellExecutor contract and AIDL transport protocol.
Shizuku SDK and AIDL types preserve host ClassLoader identity; these implementation classes
stay private. Native binaries, Ubuntu archive/licenses and the Shizuku provider manifest
are packaged here. API 11 retains the rejection of dependencies on the former shared
execution implementation classes.

Desktop native process tests, Android request/quoting unit tests and Android CPU/process
instrumentation tests live with their implementations. Actual root authorization and
foreground permission UI still require independent device verification.

`DesktopNativeShellPlugin` accepts an absolute workspace String and constructs its
executor during each activation. The desktop host selects this formal entry instead of
preconstructing an executor. Real JAR tests cover independent private instances, failed
replacement recovery, active-process cancellation/exit, disable/restore and stale calls.
Pure configuration rejection preserves the exact current service and active process.
If candidate apply has already retired the old mount, recovery resolves a fresh service
generation and does not revive disposed handles.


Shizuku starts the stable host `PrivilegedPluginUserService` transport bridge, owned by
`:plugins:platform-android`. Binder protocols and `PrivilegedPluginEngine` live in
`:plugins:api`; the private entry selects the host through the SDK class-name constant. Each call
has its own tag and deploys verified APK bytes through file descriptors; the private engine
is loaded child-first, preserving only the shared SDK boundary. The engine cancels and joins
operations before APK/resource cleanup. External entries must pass their captured origin:
`AndroidShellExecutors(activity, modeReader, PluginCodeOrigin.current(ctx))` and
`AndroidUbuntuShellExecutor(context, modeReader, PluginCodeOrigin.current(ctx))`.
Missing external origin rejects deployment; built-ins explicitly use application APK/splits.
Remote Ubuntu resource/native executable lookup uses the verified deployment.

Real APK/Binder tests load independent private versions and the actual shell engine, including
active-process close/join and failed-digest cleanup. They execute at the test application's
actual UID. Live Shizuku UID-2000 connection, remote Ubuntu installation and privileged external PRoot execution still require
independent device verification.


`AndroidNativeSettingsShellPlugin` and `AndroidNativeSettingsUbuntuShellPlugin` are
actual external APK entry classes. They acquire `AndroidPluginHostInputs` and the
current `PluginCodeOrigin`, allocate executors per activation, and publish the neutral
`KcodeShell` / `KcodeUbuntuShell` backend. Reads and commands run under one operation
owner; settings withdrawal cancels calls and makes these entries Pending. The Android
host uses these same entries when settings-backed mode is selected. Explicit custom
mode policies use `AndroidNativeShellPlugin` / `AndroidNativeUbuntuShellPlugin`.

The system executor now retains only application context. Its old Activity overloads
remain available. The real APK test loads the product settings-aware entry directly,
with no reflection into InstrumentationRegistry, and exercises app-UID execution,
disable/reenable, settings replacement, and active native process cancellation on
uninstall. Ubuntu entries now own ZIP resource readers for the captured APK dependency graph,
including their open streams and extracted native files. Local and remote rootfs reads
never fall back to host assets when an imported graph lacks the resource. Remote engines
no longer use Android's shared AssetManager cache. Retirement joins active commands
before deleting the extracted files. Each environment has its own temporary directory;
the persistent rootfs survives retirement, and an OS file lock serializes installation
across independent plugin class loaders.

API 15 provides a generic host ARM64 static-image bootstrap. Imported App-mode PRoot
starts through the system linker; its private loader is exposed through that command's
filesystem namespace and mapped by the bootstrap. The host bootstrap contains no PRoot
or Ubuntu policy. The original extracted-loader execve and memory-file attempts were
denied by Android; the namespace/static-image path now passes real-device complete Ubuntu
commands, including apt and Python. The
[UID-2000 verification entry](../../docs/verification.md#privileged-verification) covers private shell/Ubuntu execution,
command retirement, stale references, independent engines and resource cleanup. Shizuku cross-process
authorization/delivery and root execution remain separate device verification gates.
