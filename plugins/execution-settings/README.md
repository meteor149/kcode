# Shell execution settings policy

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
