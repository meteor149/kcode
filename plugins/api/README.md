# Plugin SDK

This module defines the types that the native host and independently loaded plugins
share. It consolidates the former `shared` contracts with Cordis service keys,
extension events, host bridges, and the reserved Harness APIs.

`PluginHostApiPackages` shares Koog framework identities, including its standard file-tool
extension. The SDK declares `agents-ext` explicitly so independently loaded tool consumers
do not depend on a statically linked feature module to supply shared framework classes.
Vendor Koog clients, their OpenAI-compatible base, and AWS/Smithy implementations are
private dependencies of the owning model adapter package. `LLMClient`, HTTP factories,
model/prompt/tool protocols and `kotlin-logging` retain their shared framework identities.
Desktop DataStore and Okio remain SDK peers when settings implementations are private.
SQLite's bundled driver/JNI and async support are explicit SDK peers. Room runtime,
generated databases and repository implementations remain private to history packages.
Android Shizuku client/provider libraries are explicit SDK peers. The native host adapter
declares `ShizukuProvider`; removing an execution implementation does not remove its
shared Binder/client identities or installed bridge component.

Android Core/Core KTX and versioned-parcelable are explicit SDK peers for native windows
and FileProvider components. Core's legacy `android.support` binder/parcelizer classes
already use the Android host namespace; their corresponding Core/Parcelable types must
retain the same host identity.

This shared boundary is Plugin API 63; older
external packages must be rebuilt against its generated SDK ABI.
Product tool implementations remain private to their plugin packages.

API 43 introduced `ShellModePolicy` and `KcodeShellMode`. Native Shell consumers declare this
execution-identity policy as an injected service. Settings and caller callbacks may provide
it independently; withdrawing a policy suspends executors and cancels their owned work.

`ConversationOverlayHostState.bind/current` exposes borrowed runtime foreground state
and the read-only committed UI projection to independently loaded overlay registries.
The binding owns no native resources; private providers still own their controller,
window and coroutine cleanup. Registry replacement preserves the same host state.

- Agent, tool, workspace, shell, skill, model, and conversation contracts describe
  capabilities supplied by plugins.
- History, settings, codecs, search settings, and image export contracts
  let providers and consumers exchange data without importing provider implementations.
- Localization contracts, text keys, and Compose adapters share translation identity;
  dictionaries and language selection policy belong to `plugins/localization`.
- Conversation state and application host options are interoperability contracts.
  Default layout, sidebar, pages, theme, and navigation protocols belong to the
  optional `plugins/default-ui-api`; reusable controls belong to `libraries/ui`.
- Native Binder protocols and leases share OS resources across plugin generations.
  Installed Android components belong to `plugins/platform-android`.

These contracts are loaded once by the host and exported through
`PluginHostApiPackages`. Copying them into each private JAR/APK would create distinct
class identities and break service lookup, casts, and cross-generation ownership.
Provider implementations remain independently loaded and replaceable.

The move retains package names and public signatures, including existing product
compatibility fields and localization keys. It does not turn those contracts into
implementation plugins or generalize all existing product schemas. Current Plugin API is 42;
no `:shared` Gradle dependency is required. Package imports and composition batches use
`AgentPluginManager.importPackages` / `applyChanges`. `PluginPackageInstallation` locks archive,
variant, SDK ABI and package dependencies in the same persisted snapshot as native descriptors.
The optional `KcodePluginPackages` service performs host-specific staging/verification; generic
manifest/ZIP tooling belongs to Cordis Kotlin rather than this SDK. See
[the package format](../../docs/plugin-package-format.md).

Run SDK tests with `./gradlew :plugins:api:allTests` (Windows: `gradlew.bat`, JDK 21).

UI contracts own their `UiSlotKey<T>` IDs and value types. `KcodeUiContributions.snapshot()`
prepares every registered projection; `snapshot(key)` prepares only one capability,
returns null when absent, and does not invoke unrelated providers. Both execute provider
code outside the registry mutex and retain the same cancellation/join semantics on
withdrawal. Only the owning UI interprets the returned value. Agent/tool event KDoc
records their dispatch modes and delegation semantics.

API 44 introduced the Cordis execution-neutral runtime and target contracts (architecture
family/bitness, system and Linux distribution version ranges, and required system features)
in the existing shared `org.cordis` boundary. Rebuild
external artifacts against the matching SDK/framework fingerprint.

API 45 adds `SettingsUpdateRegistry` / `SettingsUpdateTransform` and the `updates` registration
boundary on `KcodeSettingsCommands`. `SettingsUpdate.suppliedFields` exposes transport field
identities; feature packages own their validation. The default UI SDK removes feature fields
from `SettingsPageRequest` and makes section visibility composable. Rebuild external packages.

API 46 makes `AgentToolContext.coordinator` nullable: a root turn can execute without a
subagent provider. Consumers contribute subagent tools only with that capability present.
No new namespace is shared; the existing execution and default UI contracts keep
their host identity. Rebuild packages against the matching ABI fingerprint.

API 47 narrows Koog exports to framework namespaces and explicit client protocol identities.
Vendor client namespaces and AWS/Smithy are no longer shared; packages must carry their
private dependency closure and be rebuilt against the new generated ABI fingerprint.

API 48 versions the default UI conversation contribution constructor changes: generic
placement anchors and optional shared haze context replace page-owned Subagent status UI.
No feature renderer or coordinator implementation is added to shared exports.

API 49 makes the default UI navigation chat request optional, so Generation withdrawal
does not revoke the whole default application. The shared default UI namespace
already covers this contract; no new private implementation exports were added.

API 50 makes default UI history/session projections optional. This permits settings
and unrelated routes to survive conversation-provider withdrawal. Old packages must
be rebuilt against the shared ABI; private implementations remain privately loaded.

API 51 adds feature-owned default UI text contributions and shared numbered text
interpolation. Rendering dictionary lookup may return null for unknown keys; strict
headless lookup still rejects them. Defaults and formatting do not publish a
Localization provider or export any private dictionary implementation.

API 52 adds `AppSettingsStore.transaction`, single-use `SettingsTransaction` commits
and opaque `SettingsPatch` differences. `KcodeSettings` publishes one coordinated
store: its owner encloses load, feature transforms and durable save, including queued
transactions. Teardown cancels and joins that owner before underlying storage release.
Raw persistence backends implement load/save; consumers use the published store.
Recursive transactions and escaped or repeated commits reject work. Existing settings
namespace exports cover the new contracts; rebuild external packages for this ABI.

API 53 changes default UI conversation presentation and request constructors. Features
prepare multiple generic placement projections, and the default root no longer receives
an exporter. Export presentation is private to its feature package. The existing shared
default UI namespace covers the changed contract; private UI classes remain unexported.

API 54 replaces fixed `SettingsUpdate` constructor fields with an immutable mapping of
feature-owned string identities to supplied string values. Empty strings remain explicit
updates. Features register accepted fields and validate their values; the SDK enumerates
no model/search fields. Source and returned maps cannot change an admitted request, and
the request's diagnostic string prints identities only. Existing wire identities remain
compatible, while callers must rebuild and construct `SettingsUpdate(mapOf(...))`.

API 55 adds `StoredAppSettings.namespaces`, a mapping of feature identities to opaque
JSON objects. Generic patches retain complete namespace/key identities and preserve
unrelated nested fields, explicit empty values, JSON nulls and unknown feature documents.
Schemas and default values belong to the feature. Fixed legacy members currently remain
for the subsequent feature migration; this addition does not finish their removal.
The constructor/serialization change requires external packages to be rebuilt. JSON
framework identity was already shared through `PluginHostApiPackages`.

`SettingsPatch` merges newly created JSON objects into the latest committed snapshot by
field, including nested objects. Creating an empty object preserves concurrent contents
while still creating it on an unchanged snapshot. Explicit JSON nulls and arrays replace
the corresponding value; key removals remain deletions. This preserves unknown values
when a UI draft predates the first command that creates the same feature namespace.
Feature-owned validation through the durable mutation boundary remains separate work.

API 56 adds the optional `TranslationCatalog.languageSettings` configuration projection
and neutral `LanguageSettingsPolicy` contract. A catalog may render without configuration
support (the default is null); providers own persistence interpretation and update validation.
Root rendering consumes the provider's preferred language and otherwise the catalog default,
without decoding fixed language fields. The feature owns policy lifetime; stale references
reject work after withdrawal. Existing localization/settings package exports cover these types.
External packages must be rebuilt for the new property/interface ABI.

API 57 adds optional `ShellModePolicy.settings` and `ShellModeSettingsPolicy`, exposing
feature-owned immutable snapshot resolution/updates separately from asynchronous execution
mode reads. Callback-only policies retain a null configuration projection. The default UI
frame also gains an optional shell settings policy. Both public changes require package
recompilation; existing API/default UI SDK exports cover their types. Configuration lifetime
belongs to the feature, and the root consumes prepared optional capabilities.

API 58 adds optional `InteractionPolicy.settings` / `ToolPermissionSettingsPolicy`.
Feature implementations own permission schemas; neutral consumers may resolve committed
snapshots or prepare proposals without reading concrete persisted fields. Retained policies
must reject operations after withdrawal. Default UI ABI 58 replaces permission-specific
chat parameters with `SettingsEditorProjection` and the `ComposerActions` contribution
anchor. `ApplicationHostOptions.conversationSettingsControlsAvailable` enables generic
conversation settings contributions. External packages must rebuild against API 58.

API 59 removes every fixed feature field from `StoredAppSettings`. Its public constructor
contains `namespaces` and raw `legacyValues` only. Feature packages interpret old values;
infrastructure preserves unknown JSON without supplying product defaults. SDK, storage and
external feature packages must rebuild for the changed constructor/serializer ABI. Shared
exports remain the existing settings/API namespaces; migration codecs are private.

`TranslationCatalog.configuredLanguage(settings)` delegates persisted interpretation to its
optional `LanguageSettingsPolicy`; rendering-only catalogs use their declared default.
Headless schedule, approval and notification consumers use this neutral projection rather
than decoding language fields. Withdrawal still revokes strict catalog/policy calls.

API 60 adds `SettingsMutationValidator`, `SettingsMutationRegistry`, and
`KcodeSettings.mutations` / `mutationStore`. Alternative roots should submit proposals
through `mutationStore`; `store` remains the shared transaction boundary for validated
feature command handlers and opaque persistence operations. Register one stable namespace
per feature and collect its disposer in the owning effect. Validation receives the latest
committed and merged proposed documents, invokes callbacks outside registry locks, and
holds every affected registration through durable save. Historical values are read-only
on the mutation path; unchanged unknown/disabled namespaces survive. Missing namespace
owners reject writes rather than permitting stale feature proposals. Registrations may
not dispose themselves or close their registry during validation. Existing SDK settings
exports cover these contracts; all external packages must rebuild for ABI 62.

API 61 removes the Web Artifact storage and Web container contracts, runtime projections,
and default UI slots. Rebuild external packages against the new SDK; packages using these
removed capabilities are no longer supported.

API 62 makes `KcodeUiContributions` and default `KcodeUiSlots` abstract shared service contracts;
provider-private implementations own their storage and lifetimes. `ApplicationServices` exposes
prepared UI, model and command snapshots during the same short-lived frame preparation window.
Default navigation gains page-owned presenters and a generic chrome/context request. Existing
external packages must rebuild, and alternative registry providers must implement the service contracts.
The shared export namespaces remain unchanged; private registry packages are not host exports.


API 63 adds provider-owned default UI icon identities and generic region-choice metadata
to `ModelProviderSpec`. Registration validates choices/defaults and copies supplied metadata.
The generic model manager consumes that declaration; vendor implementations remain private
and live in independent `plugins/llm/*` modules. Rebuild external packages for ABI 63.

API 63 also makes `KcodeLlm` an abstract SDK service. Registry state and client lifetime
wrappers live privately in `llm-core` and close with their owning provider.
