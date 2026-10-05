# Native product composition

`nativePluginBundle` selects ordinary replaceable providers and consumers for Android
and desktop. Runtime lifecycle implementation belongs to `plugins/runtime`.
`KcodePluginRuntimeConfig.bundle` supports an alternative composition;
`KcodePluginProfile` controls inclusion, disabling and overrides.

This composition layer may reference concrete implementations. Feature consumers depend
on SDK contracts instead. The trusted catalog in the root Gradle build defines release
boundaries and platform variants; static mounts are omitted when those packages are
offered. Product dependencies are compile-only. Production hosts retain SDK contracts,
composition/installation infrastructure and platform input adapters.

`feature.web-search`, `feature.goal`, `feature.schedule`, `feature.subagents`, `feature.localization` and
`feature.markdown`, `feature.conversation-export` own their related domain
providers and contributions within one install/enable boundary. Internal children react
to optional presentation and registry availability. Settings forms are owned by their
features. The neutral UI registry and default projection remain separate infrastructure.

`NativeBuiltinAliases` migrates retired release identities. Recovery preserves disable
choices and external dependency graphs, retaining a whole former feature group when any
member is required and suppressing the aggregate by default until migration is possible.
Legacy standalone settings-item aliases retire to empty sets.

`nativeProfileBundles` produces the ordered data layers `kcode.base`, `kcode.agent` and
`kcode.default-ui` from the offered native module catalogue. `nativeProfileTemplate` selects
them for the initial `native` Profile and preserves legacy settings/history scopes. These
layers describe entries; allocation happens later through the managed Profile runtime.
Successful commits freeze their contents. The distribution catalogue and profile instance
tree remain separate, so one release can supply independently configured instances.

Native factories with `includeDefaults = false` do not stage the default catalog or mount
default product features. A supplied composition store still mounts the installation
service, so explicit imports and persisted enable choices work in alternative products.
Tests cover headless composition, package migration and production-classpath loading.

The Android factory retains the privately loaded `interaction` package for
settings-backed caller approvers and publishes the borrowed callback as `KcodeToolApprovals`
through an SDK-only host adapter. The feature continues to own its permission schema and UI;
the host never loads the feature implementation directly. In-process alternative bundles
can select `SettingsApproverInteractionPlugin` through a `compileOnly` dependency. Explicit
callback-only policies remain neutral borrowed inputs without implicit settings controls.
