# Settings infrastructure

## Generic settings repository

`SettingsProviderPlugin` publishes a transaction-capable `KcodeSettings` store. Borrowed
stores stay caller-owned; absent inputs allocate independent memory resources.
`FactorySettingsProviderPlugin` owns bounded allocation, admitted-operation cancellation,
joining and resource release, and attempts every cleanup on failure. Disabling a provider
allocates nothing. Desktop scopes cancel/join before reopening the same DataStore file;
Android SDK leases serialize shared MMKV access and close the native handle on final release.

API 59 `StoredAppSettings` contains only `namespaces` and an opaque `legacyValues` JSON
object. Storage has no model, search, language, execution or permission defaults. Feature
providers own their schemas/defaults and interpret historical values only while their
namespace is absent. Explicit empty/null/numeric values and unknown legacy root fields are
preserved during import; validation belongs to the feature, not the repository.

Both persistent backends publish `settings_snapshot.v2` as their single complete commit.
Reads prefer v2, import v1 JSON without adding missing fields, then import historical scalar
keys only when no committed snapshot exists. Malformed committed records fail explicitly;
no fallback resurrects older credentials or permission settings. Known scalar names are a
bounded read-only migration table. Writes no longer fan out to legacy keys, remove old
credentials, choose search providers, or overwrite unknown native preferences. V1 records
and scalar values remain untouched. A failed first/new commit leaves the previous durable
state available for a fresh provider. Existing MMKV IDs, deployment paths, encryption and
Keystore alias remain unchanged; no separate plaintext credential file is created.

DataStore protects/reveals the whole v2 document through its configured codecs. Historical
credential scalars are revealed while importing; historical complete v1 records are revealed
as whole documents. The native desktop provider retains its application-data policy.
Memory save/load deeply detach opaque legacy JSON and namespace containers.

Native entries are `AndroidNativeSettingsPlugin` (`Unit`, leased host inputs) and
`DesktopNativeSettingsPlugin` (absolute deployment path). The dual-target release is
`provider.settings.platform`; production hosts exclude its private implementation classes.
Explicit caller-owned stores/factories bind through the SDK-only composition adapters.
MMKV/DataStore framework identities are host-shared; codecs and provider state stay private.
Provider replacement revokes stale load/save/protection access without deleting durable data.

Tests cover scalar/v1 migration, actual absence versus empty values, unknown roots/namespaces,
empty unloaded-provider credentials, single-commit false/throw/cancellation failures, retry,
corruption, protection failure and private-package withdrawal/reopening. Native MMKV fault
fixtures compile for device execution and verify old data after native close/reopen. Desktop
DataStore tests use real files. Test-only `LegacySettings` fixtures construct historical bags;
they do not reintroduce a fixed production settings DTO.

```sh
./gradlew :plugins:settings:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:settings:compileDebugAndroidTestKotlin
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidSettingsStorageTest,ai.meteor.kcode.plugin.AndroidCustomProviderSettingsTest
```

## Settings command consumer

`consumer.settings.commands` consumes the current `settings` service and provides
`KcodeSettingsCommands`. Its operation owner covers the complete load/validate/save
command, serializes commands, joins cancellation cleanup, and revokes old handlers.
A committed result is returned only after save succeeds. Feature-owned contributions register supported fields and transforms through `KcodeSettingsCommands.updates`. Validation uses the
runtime's committed model catalog, including plugin-defined provider identifiers;
there is no static provider/model table fallback.

The stable request/result types live in shared settings contracts. The dispatcher is private to this module; model/search update policy belongs to the corresponding feature packages. Model/search command
validation tests migrated from the Android host; broadcast parsing, DUMP permission,
and result delivery remain native host bridges.

`ApplicationContent.updateSettings` resolves the current handler and committed
catalog, with backend work outside the runtime mutex. The Android receiver calls
this host entry, then emits SettingsChanged only on success. It never constructs an
MMKV store. Missing settings/command providers fail, and a replacement redirects
subsequent commands to its storage implementation. The Android application waits
at most five seconds for runtime publication during asynchronous startup; no active
runtime causes an explicit open-kcode error, never a storage fallback. Detaching an
old Activity cannot clear the newly published runtime.

Coverage includes validation, write failure, withdrawal during a suspended save,
private APK implementation with shared request/result types, and real shell-UID
broadcasts through the registered Android receiver. Compose UI validation and CLI
parsing are separate concerns. API 52 coordinates commands and UI differences through
the store published by `KcodeSettings`; transforms stay alive through its durable
commit. Direct writes to an independently retained raw backend do not participate.
UI patches still need the feature validation routing described in the remediation plan.

```sh
./gradlew :plugins:settings:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidSettingsCommandsApkTest
./gradlew :apps:androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.AdbSettingsPluginTest
```

The last two filters target different test APKs and must run separately.

The dispatcher requires only `settings`, never `searchSettings` or model policy services.
Unsupported fields fail before any transform/save. A selected contribution owns the whole
transaction through durable save; withdrawing it cancels and joins in-flight operations.
Mixed requests validate against one registry snapshot, so missing features cannot cause
partial saves. Disabling search does not disable model updates, and vice versa.

API 54 accepts `SettingsUpdate(mapOf("plugin.example/field" to "value"))` without
changing the SDK request type or Android transport. Feature registrations own accepted
identities, including existing model/search wire names. Unsupported identities reject
the complete update before any transform/save. Values and field lists are request
snapshots; feature callbacks cannot mutate the caller's admitted envelope. This command
extensibility does not yet remove the legacy fixed storage fields or route UI differences
through feature validation; those migrations remain in the remediation plan.
