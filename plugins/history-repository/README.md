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

## Distribution artifacts

`:plugins:history-repository:prepareProviderHistoryPlatformDesktopJar` produces a reproducible JAR containing
this module and private Room runtime/common and collection classes. Conflicting private classes/resources fail the build.
`:plugins:history-repository:prepareProviderHistoryPlatformAndroidApk` produces the equivalent
APK from the existing Android library AAR and its private dependencies. Neither
artifact includes SDK, Cordis, Kotlin, coroutines or SQLite/JNI implementations.

`DesktopNativeStorageTest` loads the built history JAR, checks private Room/collection
and shared SQLite identities, and verifies durable data after withdrawal/remount.
The default catalog contains `provider.history.platform` with both native variants.
Production hosts exclude this module and its Room dependencies from their classpath.
Desktop configures the existing `~/.kcode/history.db` path; Android uses the existing
`kcode_history.db` in the host application's database directory. The database lives
outside immutable plugin generations. The shared SQLite/JNI peer was introduced in SDK API 39 (current API 43);
the schema remains version 6. Explicit caller inputs bind through an SDK-only bridge
with owned operation cancellation and resource closure, without importing Room.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.history.platform

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
