# Generic settings repository

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
./gradlew :plugins:settings-repository:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:settings-repository:compileDebugAndroidTestKotlin
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidSettingsStorageTest,ai.meteor.kcode.plugin.AndroidCustomProviderSettingsTest
```
