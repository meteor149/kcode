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
locations. Android workspace scope enforcement and runtime Profile switching remain pending.
Privately loaded storage providers accept their legacy Unit defaults or machine-supplied
String identities/paths; those machine values are not copied into portable Profile intent.

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
