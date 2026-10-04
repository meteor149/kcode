# History repository provider

This module owns the Room repository, DAO/entities, generated constructors, schemas and
migrations. Shared retains history DTOs and repository contracts only. Native factories
configure database locations and allocate a new repository when the provider mounts;
disabling it prevents allocation entirely. Existing native database paths are preserved.

`FactoryHistoryProviderPlugin` consumes the stable `HistoryRepositoryFactory` contract.
Allocation is bounded and collected before publication, including cancelled assembly.
Operations are revoked, cancelled and joined before the native database closes on IO.
Remount reopens the same file. `HistoryProviderPlugin` accepts an explicit borrowed
repository; that repository's resource lifetime remains with its caller.

The Android SQLite JNI bridge is host shared; the product repository, Room generated
classes and DAO stay private to an isolated plugin APK. Schemas 1–6 moved without
changes to tables, migration policy or identity hashes.

```sh
./gradlew :plugins:history-repository:desktopTest :plugins:platform-desktop:test
./gradlew :plugins:platform-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.meteor.kcode.plugin.AndroidHistoryProviderTest
```

Without an explicit native or borrowed backend, the bundle mounts
`memoryHistoryRepositoryFactory()`. Every mount owns its own conversations, atomic message
batches, goals, presentation/results, pinned state, and scheduled tasks. Withdrawal releases
that ephemeral state; native Room reopens persistent files. The old no-op history backend
is an explicit `EmptyHistoryFixture` in the test-support module, never a product fallback.

Native default compositions use `AndroidNativeHistoryPlugin` (`Unit`, leased SDK
host inputs) and `DesktopNativeHistoryPlugin` (absolute deployment path string).
The entry owns factory creation and delegates allocation, revocation and release
to the owned provider. Default hosts no longer supply a business factory closure.
Explicit caller-owned stores/repositories and custom profile overrides remain supported.
Real JAR/APK formal-entry tests cover private implementation identity, stale-call
rejection, durable data after remount, and uninstall. Desktop storage packages
include the private Room runtime/common and collection dependencies; DataStore
and its Okio types preserve the host SDK identity.
