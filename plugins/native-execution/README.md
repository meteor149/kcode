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
stay private. Native binaries and Ubuntu archive/licenses are packaged here. The Shizuku
provider declaration belongs to the native host adapter. API 11 retains the rejection of dependencies on the former shared
execution implementation classes.

## Independent Android artifact

`:plugins:native-execution:prepareProviderShellUbuntuAndroidApk` builds a separate APK from the
real provider module. It carries private archive codecs (Commons Compress and its
dependencies, plus XZ), the ARM64 PRoot executables, Ubuntu rootfs and licenses.
SDK, Cordis, Kotlin/coroutines and Shizuku classes are compile-only host identities.
The distribution manifest removes `ShizukuProvider`: only the installed native host
publishes that bridge. The SDK supplies Shizuku client/provider peers, and
`platform-android` owns the installed provider declaration. This implementation module
no longer supplies that declaration.

`AndroidSettingsShellTest` loads the actual preparation archives for its native system
Shell and Ubuntu entries. It verifies app-UID shell execution,
Ubuntu/Python startup, settings rebinding, withdrawal, command cancellation, native
temporary-file cleanup, and preservation of installed rootfs. This does not establish
Shizuku UID-2000 authorization or root execution. The framework/class and resource audit
checks both entries, private codecs, absence of shared defined types, and exact hashes
for the two executables, Ubuntu archive and both licenses.

`:plugins:native-execution:prepareProviderShellPlatformAndroidApk` builds the ordinary Shell APK from the same compiled
provider AAR after removing deployment-only assets and JNI. Private provider classes and
archive codecs remain intact; this is a resource split, not a second source implementation.
The resulting APK carries neither Ubuntu rootfs/licenses nor PRoot executables and declares
the separate package name `ai.meteor.kcode.external.nativeshell`.
`:plugins:native-execution:packageProviderShellPlatform` packages desktop and Android ARM/x86, 32/64-bit
variants in one release, with the Android variant using the Unit-configured `AndroidPackagedShellPlugin` entry.
The real device fixture imports the archive, checks resource independence, shares Shizuku
SDK identities, executes an app-UID shell, rebinds settings and waits for process cancellation
before uninstall.

The trusted `provider.shell.platform` release now includes desktop and Android variants.
Android's factory loads the bytecode-only APK and no longer mounts a linked system Shell.
API 43 adds `KcodeShellMode`/`ShellModePolicy`, injected by the packaged entry. Settings-driven
hosts load `policy.shell-mode.platform`; explicit callback hosts provide the same SDK service
through `HostShellModeInputPlugin`. Disabling or replacing the policy suspends the executor
and cancels/joins its work before policy release. Both policy and executor reject stale calls.
`AndroidPackagedUbuntuShellPlugin` consumes the same SDK policy and owns its Ubuntu executor.
The trusted `provider.shell.ubuntu` package is Android-only ARM64. Host-aware staging skips
its payload on unsupported architectures before reading resources, leaving service-dependent
tool consumers Pending. The Android host no longer links this implementation module.

`:plugins:native-execution:packageProviderShellUbuntu` wraps the built APK as an Android-only ARM64
release. Its SDK ABI/configuration and payload are verified through normal
`AgentPluginManager.importPackages`; the Ubuntu device test now exercises that archive
path, including variant selection, before starting private PRoot/Python and revoking it.
The same release is consumed by the default catalog with the explicit ARM64
selector. Explicit imports on incompatible hosts still fail normal manifest validation.

## Independent desktop release

The trusted catalog includes `provider.shell.platform` as a dual-target `.kplugin`,
with `DesktopNativeShellPlugin` as its String-configured entry. The native desktop factory
stages the verified JAR with its existing absolute workspace configuration, including
profiles with default product composition disabled. Saved configuration and enable state
continue through normal package transactions. The production desktop host does not link
native execution implementations; tests declare their implementation dependencies explicitly.

Desktop native process tests, Android request/quoting unit tests and Android CPU/process
instrumentation tests live with their implementations. Actual root authorization and
foreground permission UI still require independent device verification.

`DesktopNativeShellPlugin` accepts an absolute workspace String and constructs its
executor during each activation. The desktop host loads this formal entry from the verified
archive. Real archive tests cover independent private instances, failed
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

The legacy Unit-configured `AndroidNativeSettingsShellPlugin` and
`AndroidNativeSettingsUbuntuShellPlugin` now consume `KcodeShellMode`, matching the
packaged entry points. They forward execution identity through the SDK policy instead of
interpreting fixed storage fields. Explicit compositions must supply that policy separately;
settings-backed policy/schema/UI ownership belongs to `native-execution`, and callback
policies may run without a settings repository. Legacy entry class names/configuration stay
available. Production consumers add no implementation dependency on the settings provider.

## Shell execution settings policy

`SettingsShellModePlugin` is a Unit-configured entry for `policy.shell-mode.platform`.
It requires `KcodeSettings` and provides the `KcodeShellMode` service. Each read
uses the current stored mode; unknown codes retain the existing App fallback. It owns
its reader lifetime, cancels/joins active reads during withdrawal, and rejects stale readers.
Settings replacement remounts the policy and suspends/rebinds dependent native Shells.
It borrows the settings repository and does not close or migrate its data.

The default Android catalog packages this provider independently. Its APK has compile-only
SDK identities and no native resources. Desktop compiles the shared implementation but
does not select the default Android policy package. Custom Android callback compositions
provide the same SDK service through `HostShellModeInputPlugin`, an owned SDK-only adapter,
without serializing a closure or importing concrete executors into the factory.

`AndroidPackagedShellPlugin` consumes `KcodeShellMode`; withdrawing the policy cancels
native work before releasing it. `AndroidSettingsShellTest` imports the real policy/Shell
archives, checks unknown-mode fallback, live settings reads, stale readers and rebinding.
Its callback test runs without a settings provider and checks actual process exit when
the callback policy is disabled. Root/Shizuku authorization requires separate device evidence.

The same feature JAR/APK contains its default settings form. The feature entry mounts an
optional settings contribution as a child Fiber; missing `uiSlots` suspends only this
child. Disabling/replacing the feature withdraws its setting item, without withdrawing
the settings page. Restoring the feature registers a fresh contribution. There is no
separate UI-only settings package.

The settings contribution additionally follows actual `KcodeShell`/`KcodeUbuntuShell`
capabilities. Either backend keeps one shared section registered; withdrawing the last backend
removes the section and revokes retained callbacks. A headless policy can remain mounted without
advertising execution UI. `SettingsShellModeOwnershipTest` covers both withdrawal orders and
recovery without duplicate registrations.

Settings English defaults live in `src/main/ui-texts/strings_en.xml` and compile
into this feature's private bytecode. They register with the section and disappear
with it; the page does not supply this feature's field labels or read host resources.

API 57 exposes optional `ShellModePolicy.settings: ShellModeSettingsPolicy?`; callback-only
policies use null and offer no settings form. The settings provider owns a revocable private
policy for `feature.execution-settings` JSON (`mode` string). Execution reads, form
selection/description and the default root's host-mode notification use this projection.
The root captures it during frame preparation as an optional default UI service, rather
than reading persisted mode fields. Its absence does not become a root requirement.

With no namespace the policy reads its historical key from opaque `legacyValues`; new updates write only the feature
namespace. Existing empty documents use the feature App default, unknown codes remain
saved while resolving to App, and malformed owned types fail. Unknown fields and other
feature namespaces survive updates. Withdrawing the provider projects null and rejects
retained settings callbacks, alongside cancellation/join of asynchronous mode reads.
The real private APK test includes namespace selection through the native policy; Android
instrumentation source compilation is separate from device execution evidence.

All native settings entry aliases and `capability-providers` factory adapters now consume
`KcodeShellMode`. Caller factories explicitly compose their settings-backed or callback
policy; consumers import only SDK contracts. Callback-only policies can operate without a
settings store. Desktop tests cover both system/Ubuntu adapters, namespace-selected mode,
settings replacement, policy replacement/withdrawal/recovery and owned cancellation.
Permission configuration/control ownership and fixed-field/default removal are complete in
API 58–59. Durable feature validation of UI commits remains unfinished. Rebuild packages
for the current ABI 59.

API 60 registers feature-owned namespace validation with `KcodeSettings.mutations`.
The default root uses `KcodeSettings.mutationStore`: proposals merge into the latest
transaction state before validation, and the registration lifetime covers durable save.
Withdrawal rejects subsequent edits of this namespace while preserving its saved data;
other active settings owners remain usable. Validation is independent of settings-command
and default UI availability. Feature rules validate types and newly selected values while
retaining unchanged future identities and unknown document fields.

Execution mode policy, validation, settings UI and their tests live in this module alongside
the native providers. `policy.shell-mode.platform` remains an independently replaceable entry.
