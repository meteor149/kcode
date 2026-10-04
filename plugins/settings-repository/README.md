# Settings repository providers

This module owns Memory, MMKV and DataStore setting implementations, scalar keys/defaults,
provider-key maps, Android encrypted-key bootstrap policy, and resource allocation.
Shared retains DTOs/contracts. The Android MMKV lease is owned by `:plugins:api`, with no
application keys or codecs. Native hosts describe factories and allocate no stores eagerly.

`StoredAppSettings()` is an empty neutral SDK snapshot. `DefaultSettings.kt` owns product
choices for new installations and absent persisted fields. MMKV legacy scalars and desktop
DataStore use the same private defaults. Older partial MMKV JSON snapshots merge only
absent fields with those defaults; explicit empty values remain empty and corrupt snapshots
still fail. Current snapshot writers encode every field and preserve the stable scalar keys.

`FactorySettingsProviderPlugin` consumes the stable `SettingsStoreFactory` contract. It
collects bounded allocation before publication, revokes and joins calls before resource
release, and aggregates cleanup failures. A disabled provider allocates nothing. Borrowed
stores supplied to `SettingsProviderPlugin` remain caller-owned; missing input creates an
owned memory resource, released and cleared on withdrawal.

Desktop scopes cancel/join before another DataStore opens the same persistent file.
Android leases serialize access to a shared native handle; old Activity/resource release
cannot close a sibling's handle. Final release closes MMKV. Persistent file paths, native
setting keys/defaults, encrypted bootstrap format and Keystore alias remain unchanged.
MMKV/DataStore SDK binding is host shared; plugin codecs remain child-first. Plugin API 11
rejects old shared implementation dependencies before loading.

```sh
./gradlew :plugins:settings-repository:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidSettingsStorageTest,ai.meteor.kcode.plugin.AndroidCustomProviderSettingsTest
```

MMKV settings retain the legacy scalar keys/defaults and add `settings_snapshot.v1`, a
small serialized committed preference snapshot. The first save preserves the complete
legacy value before scalar writes. Successful saves publish the new snapshot only after
all scalar writes succeed. Fresh/reopened providers read the committed record, so a
partial scalar failure cannot silently change model credentials, permission or execution
identity. Corrupt committed records fail explicitly. StoredAppSettings is serializable;
the private codec preserves the native Double domain and ignores unknown future fields.
The MMKV file remains Keystore encrypted; this adds no separate plaintext credential file.

Fault tests cover every failed write during initial migration and later saves, false/throw/
cancellation, fresh codec reads, retry, removed provider keys and corrupt records. Device
fault tests use actual MMKV writes with injected scalar/commit rejection, close the native
lease and reopen the file to verify that the previous full snapshot remains visible.

Native default compositions use `AndroidNativeSettingsPlugin` (`Unit`, leased SDK
host inputs) and `DesktopNativeSettingsPlugin` (absolute deployment path string).
The entry owns factory creation and delegates allocation, revocation and release
to the owned provider. Default hosts no longer supply a business factory closure.
Explicit caller-owned stores/repositories and custom profile overrides remain supported.
Real JAR/APK formal-entry tests cover private implementation identity, stale-call
rejection, durable data after remount, and uninstall. Desktop storage packages
include the private Room runtime/common and collection dependencies; DataStore
and its Okio types preserve the host SDK identity.
