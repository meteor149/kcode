# Artifact repository provider

The module owns manifest decoding and validation, web artifact resource staging,
manifest publication, rollback, and revocable repository facades. Shared code only
exports artifact models, repository/file-store contracts, and stable path constants.
Private Android/Desktop file stores belong to this module. Native entries create
the factory; each mount allocates its store and repository, and withdrawal waits for
owned calls before releasing the store. Native bundle id `provider.artifacts.platform` stays unchanged.

`FactoryFileArtifactsProviderPlugin` accepts `ArtifactFileStoreFactory` and owns
its returned resource. `FileArtifactsProviderPlugin` accepts a borrowed
`ArtifactFileStore` and does not close it; writable stores expose
`MutableArtifactRepository`, while read-only stores expose only `ArtifactRepository`.
`ArtifactsProviderPlugin` accepts an explicitly supplied repository for custom
compositions. The runtime's legacy host projection resolves the active provider
on each call and fails when absent, without retaining the original native instance.

Withdrawal cancels and waits for calls. Failed pre-commit saves roll back staging
and target resources under NonCancellable; cleanup failures are reported. Manifest
publication is the commit edge and cannot be interrupted into a dangling entry.
Once committed, later cancellation retains the artifact and its resources.

Common tests cover manifest compatibility, validation, copying, cancellation,
publication failure, and commit races. Desktop composition tests cover revocation
and rebinding. Real Android tests load this provider and its private manifest codec
from an isolated APK, and verify actual native files survive provider recreation.

API version 9 removes the former shared native stores and empty repository singleton.
Native path validation and atomic file operations are private plugin implementation.
Tree cleanup unlinks symlinks and Windows directory junctions without deleting
their targets. Tests cover actual Windows junctions, Android symlinks, private APK
factory loading, allocation cancellation, failed replacement, read-only capability,
and waiting for call cleanup before resource release.

Native default compositions use `AndroidNativeArtifactsPlugin` (`Unit`, leased SDK
host inputs) and `DesktopNativeArtifactsPlugin` (absolute deployment path string).
The entry owns factory creation and delegates allocation, revocation and release
to the owned provider. Default hosts no longer supply a business factory closure.
Explicit caller-owned stores/repositories and custom profile overrides remain supported.
Real JAR/APK formal-entry tests cover private implementation identity, stale-call
rejection, durable data after remount, and uninstall.

The default `provider.artifacts.platform` archive contains both desktop JAR and Android
APK variants. Both native hosts exclude this implementation from their runtime dependencies.
The package has no private framework dependencies: serialization and SDK contracts remain
host peers, while the manifest codec and native file operations are private. Desktop keeps
the existing `~/.kcode/workspace` path; Android keeps `filesDir/agent_workspace`. Neither
the manifest nor its resources reside in immutable plugin generations. Package withdrawal
closes the provider without deleting durable data. Explicit repositories use an SDK-only
borrowed-input bridge; in-process file-store compositions still select this module's
repository provider and own/borrow resources according to their existing contracts.

Bundled production-classpath tests save actual web resources, withdraw the provider,
check stale reads/writes and suspended tool consumers, then remount and verify the same
manifest entry and resource bytes. The manifest format is unchanged; The package migration did not change the ABI; authors use the current SDK API 43.
