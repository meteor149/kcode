# Android platform and host composition

This module supplies the Android host bridge, verified APK/dex loader, composition
persistence and native SDK input adapters. Product implementations arrive through the
trusted bundled catalog or explicitly imported packages. Shared exports are defined in
`PluginHostApiPackages`; private implementations/resources retain deployment identity.

`createAndroidKoogChatRuntime` borrows its Activity and optional caller stores/callbacks.
`profile.includeDefaults = false` selects an alternative composition without staging the
default product. A supplied settings store replaces the packaged storage provider without
transferring ownership of that caller resource.

The factory's `profileId` selects named startup; otherwise it uses saved selection or the
`native` template. The Activity forwards its `profile` intent extra. Metadata lives in
`filesDir/cordis_profiles`. Settings scopes select separate encrypted MMKV identities;
history scopes select separate Room database paths. The native template retains legacy
locations. `createAndroidProfileHost` creates the stable coordinator; the existing runtime
factory returns its chat/manager/overlay/application facades. Each product allocation receives
fresh host-input leases. Switching stages the target, joins admitted work, closes the old
product and publishes generation/selection together. Failure reconstructs the old locked
intent; failed closure/restoration refuses execution.
Privately loaded storage providers accept their legacy Unit defaults or machine-supplied
String identities/paths; those machine values are not copied into portable Profile intent.
Explicit workspace scopes bind filesystem, App system shell and local Ubuntu `/workspace`
to `filesDir/workspaces/<scope>`. Unit/default and `legacy` retain their previous locations.
The current ADB worlds cannot access app-private scoped workspaces and reject those requests
before requesting authorization; they must not silently reuse the shared ADB workspace.

`AndroidDynamicPluginController.prepareProfilePackages` uses the shared native transaction
protocol with Android descriptors and candidate dex exports. The managed runtime owns the
tree and durable generation boundary. Code resources are released only after restoration
or successful retirement. Build validation does not establish this protocol's device behavior.

`settingsBackedInteraction = true` retains the independently loaded interaction settings
feature. A custom approver is published through the SDK-only `HostToolApprovalsInputPlugin`
and `KcodeToolApprovals`; the feature owns permission schema, validation and optional UI.
The adapter owns callback operations and cancels/joins them on replacement/withdrawal.
Caller callbacks are borrowed and are never serialized as plugin configuration. Callback-only
permission modes use the explicit host policy path. `settingsBackedShell` likewise selects
the settings-owned execution policy rather than a caller mode reader.

Desktop package tests do not establish APK/dex resource identity. Run this module's relevant
`connectedDebugAndroidTest` classes on API 35+; privileged execution additionally requires
separate real Shizuku/root evidence described in the verification guide.
`AndroidProfileHostTest` covers real bundled APK providers, scope switching, stale references,
saved-selection restart, failed target reconstruction and scoped ADB rejection. Device results
are recorded separately from build evidence in `docs/profiles-implementation.md`.
The Plugin API 66 stable manager also exposes draft/clone/preview/history commands and
revision-checked activation of committed, draft or historical intent. The dedicated device
suite exercises draft activation and historical restoration with actual APK providers;
historical restoration appends a generation and preserves the Profile workspace.
Management UI and independent recovery UI remain pending.
The common host exposes metadata and explicit activation in RecoveryRequired, independently
of the withdrawn product tree. Desktop recovery tests do not prove this path on an Android
device or recover failures that happen before initial host construction.
